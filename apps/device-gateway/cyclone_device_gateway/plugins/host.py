"""The Plugin Host (plan 50 §6): starts installed plugins on this PC, keeps them alive, and contains them.

- One process per plugin, started without a shell, with a minimal environment and its data folder as working folder.
- The plugin's address, Ports key and settings go in through one stdin line (the handshake), never argv, env or a file.
- Windows: every plugin runs in a Job Object that dies with the runtime (no orphans), with memory and process limits.
- A plugin is "up" only when it serves, at the address Cyclone gave, exactly the manifest its package carries.
- Crashes restart with backoff; more than 5 restarts in 10 minutes and it stays down until the owner restarts it.
- Output goes to a small rotating log with the plugin's key and secret settings blanked out.
"""
from __future__ import annotations

import collections
import os
import signal
import socket
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from ..pc.common import redact
from ..ports.hub import http_json
from . import kpackage

START_TIMEOUT_S = 20
HEALTH_FAILURES = 3
BACKOFF_S = (1, 2, 4, 8, 16)
RESTARTS_MAX = 5
RESTART_WINDOW_S = 600
LOG_BYTES = 2 * 1024 * 1024
MEMORY_MB_DEFAULT = 1024
PROCESSES_MAX = 32


class HostError(Exception):
    """A plain reason a plugin didn't start (shown in Glass)."""


@dataclass
class Spec:
    name: str
    version: str
    entry: Path
    data_dir: Path
    manifest: dict[str, Any]
    key: str
    settings: dict[str, Any]
    hide: list[str] = field(default_factory=list)
    memory_mb: int = MEMORY_MB_DEFAULT


@dataclass
class Running:
    spec: Spec
    proc: Any
    port: int
    job: Any = None
    started_at: float = 0.0
    reader: threading.Thread | None = None

    @property
    def endpoint(self) -> str:
        return f"http://127.0.0.1:{self.port}"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def same_manifest(served: Any, packaged: dict[str, Any]) -> bool:
    if not isinstance(served, dict):
        return False

    def serves(m: dict[str, Any]) -> list[tuple[str, str]]:
        return sorted((str(s.get("port")), str(s.get("way"))) for s in m.get("serves") or [] if isinstance(s, dict))

    return (served.get("name"), served.get("version"), served.get("contract"), serves(served)) == \
        (packaged.get("name"), packaged.get("version"), packaged.get("contract"), serves(packaged))


def minimal_env(data_dir: Path, bin_dir: Path) -> dict[str, str]:
    tmp = data_dir / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    env = {kpackage.MANAGED_ENV: "1", "TEMP": str(tmp), "TMP": str(tmp), "TMPDIR": str(tmp)}
    if os.name == "nt":
        root = os.environ.get("SYSTEMROOT", r"C:\Windows")
        env.update({"SYSTEMROOT": root, "WINDIR": root, "PATH": f"{bin_dir};{root}\\System32",
                    "PATHEXT": ".COM;.EXE;.BAT;.CMD", "USERPROFILE": str(data_dir), "LOCALAPPDATA": str(data_dir),
                    "APPDATA": str(data_dir)})
    else:
        env.update({"PATH": f"{bin_dir}:/usr/bin:/bin", "HOME": str(data_dir), "LANG": "C.UTF-8"})
    return env


class _WindowsJob:
    """A Job Object: kill-on-close, a memory cap for the whole job, and a cap on processes."""

    def __init__(self, memory_mb: int) -> None:
        import ctypes
        from ctypes import wintypes

        class IO_COUNTERS(ctypes.Structure):
            _fields_ = [(n, ctypes.c_ulonglong) for n in ("ReadOperationCount", "WriteOperationCount",
                        "OtherOperationCount", "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

        class BASIC(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD),
                        ("SchedulingClass", wintypes.DWORD)]

        class EXTENDED(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", BASIC), ("IoInfo", IO_COUNTERS),
                        ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                        ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]

        self._k = ctypes.WinDLL("kernel32", use_last_error=True)
        self._k.CreateJobObjectW.restype = wintypes.HANDLE
        self._k.CreateJobObjectW.argtypes = (ctypes.c_void_p, wintypes.LPCWSTR)
        self._k.SetInformationJobObject.argtypes = (wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD)
        self._k.SetInformationJobObject.restype = wintypes.BOOL
        self._k.AssignProcessToJobObject.argtypes = (wintypes.HANDLE, wintypes.HANDLE)
        self._k.AssignProcessToJobObject.restype = wintypes.BOOL
        self._k.TerminateJobObject.argtypes = (wintypes.HANDLE, wintypes.UINT)
        self._k.CloseHandle.argtypes = (wintypes.HANDLE,)
        self.handle = self._k.CreateJobObjectW(None, None)
        if not self.handle:
            raise HostError("Windows couldn't create a job for the plugin.")
        info = EXTENDED()
        # KILL_ON_JOB_CLOSE | JOB_MEMORY | ACTIVE_PROCESS
        info.BasicLimitInformation.LimitFlags = 0x2000 | 0x200 | 0x8
        info.BasicLimitInformation.ActiveProcessLimit = PROCESSES_MAX
        info.JobMemoryLimit = memory_mb * 1024 * 1024
        if not self._k.SetInformationJobObject(self.handle, 9, ctypes.byref(info), ctypes.sizeof(info)):
            self.close()
            raise HostError("Windows couldn't limit the plugin's job.")

    def assign(self, proc: subprocess.Popen) -> None:
        if not self._k.AssignProcessToJobObject(self.handle, int(proc._handle)):  # type: ignore[attr-defined]
            raise HostError("Windows couldn't put the plugin in its job.")

    def close(self) -> None:
        if self.handle:
            self._k.TerminateJobObject(self.handle, 1)
            self._k.CloseHandle(self.handle)
            self.handle = None


class PluginHost:
    def __init__(self, logs_dir: Path, *, on_state: Callable[[str, str, str], None] = lambda n, s, d: None,
                 fetch: Callable[..., tuple[int, Any, int]] = http_json, popen: Callable[..., Any] = subprocess.Popen,
                 clock: Callable[[], float] = time.monotonic, sleep: Callable[[float], None] = time.sleep,
                 start_timeout_s: float = START_TIMEOUT_S) -> None:
        self.logs_dir = logs_dir
        logs_dir.mkdir(parents=True, exist_ok=True)
        self._on_state = on_state
        self._fetch = fetch
        self._popen = popen
        self._clock = clock
        self._sleep = sleep
        self._start_timeout = start_timeout_s
        self._lock = threading.RLock()
        self._running: dict[str, Running] = {}
        self._launched: list[Running] = []  # every live process, adopted or not, so close() leaves no orphan
        self._health_failures: dict[str, int] = {}
        self._restarts: dict[str, collections.deque] = {}
        self._next_restart: dict[str, float] = {}
        self._down: dict[str, str] = {}  # name -> why it stays down (crashed); cleared by an explicit start
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    # ---- starting ----------------------------------------------------------------------------------------------------

    def launch(self, spec: Spec) -> Running:
        """Starts the program and waits until it serves its packaged manifest at the given address. Not supervised
        until :meth:`adopt`; on any failure the process is gone and :class:`HostError` says why."""
        if not spec.entry.is_file():
            raise HostError("The plugin's program is missing. Reinstall it.")
        spec.data_dir.mkdir(parents=True, exist_ok=True)
        port = free_port()
        job = _WindowsJob(spec.memory_mb) if os.name == "nt" else None
        kwargs: dict[str, Any] = {"stdin": subprocess.PIPE, "stdout": subprocess.PIPE, "stderr": subprocess.STDOUT,
                                  "cwd": str(spec.data_dir), "env": minimal_env(spec.data_dir, spec.entry.parent),
                                  "close_fds": True, "shell": False}
        if os.name == "nt":
            kwargs["creationflags"] = 0x08000000 | 0x00000200  # CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
        try:
            proc = self._popen([str(spec.entry)], **kwargs)
        except OSError as exc:
            if job:
                job.close()
            raise HostError(f"The plugin's program couldn't start ({exc.__class__.__name__}).") from exc
        running = Running(spec, proc, port, job, self._clock())
        with self._lock:
            self._launched.append(running)
        try:
            if job:
                job.assign(proc)
            proc.stdin.write(kpackage.handshake_line(port, spec.key, str(spec.data_dir), spec.settings).encode("utf-8"))
            proc.stdin.close()
            running.reader = threading.Thread(target=self._pump, args=(running,), name=f"plugin-log-{spec.name}",
                                              daemon=True)
            running.reader.start()
            self._wait_ready(running)
        except BaseException:
            self._kill(running)
            raise
        return running

    def _wait_ready(self, running: Running) -> None:
        deadline = self._clock() + self._start_timeout
        while True:
            code = running.proc.poll()
            if code is not None:
                raise HostError(f"The plugin stopped while starting (exit code {code}). See its log.")
            status, manifest, _ = self._fetch("GET", running.endpoint + "/cyclone-plugin.json", timeout=2)
            if status == 200:
                if not same_manifest(manifest, running.spec.manifest):
                    raise HostError("The plugin serves a different manifest than its package. It was stopped.")
                return
            if self._clock() > deadline:
                raise HostError(f"The plugin didn't answer within {int(self._start_timeout)} seconds. See its log.")
            self._sleep(0.25)

    def adopt(self, running: Running) -> None:
        """Makes ``running`` the supervised process for its plugin, stopping any older one."""
        with self._lock:
            old = self._running.get(running.spec.name)
            self._running[running.spec.name] = running
            self._health_failures[running.spec.name] = 0
            self._down.pop(running.spec.name, None)
        if old is not None and old is not running:
            self._kill(old)
        self._on_state(running.spec.name, "running", "")

    def start(self, spec: Spec) -> Running:
        running = self.launch(spec)
        self.adopt(running)
        return running

    # ---- stopping ----------------------------------------------------------------------------------------------------

    def stop(self, name: str) -> None:
        with self._lock:
            running = self._running.pop(name, None)
            self._next_restart.pop(name, None)
        if running is not None:
            self._kill(running)
            self._on_state(name, "stopped", "")

    def discard(self, running: Running) -> None:
        """Stops a launched process that was never adopted (a failed update candidate)."""
        self._kill(running)

    def _kill(self, running: Running) -> None:
        with self._lock:
            if running in self._launched:
                self._launched.remove(running)
        proc = running.proc
        try:
            if running.job is not None:
                running.job.close()
            elif os.name != "nt" and proc.poll() is None:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except (ProcessLookupError, PermissionError, OSError):
                    proc.terminate()
                try:
                    proc.wait(5)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except (ProcessLookupError, PermissionError, OSError):
                        proc.kill()
            if proc.poll() is None:
                proc.kill()
            proc.wait(5)
        except Exception:  # noqa: BLE001 - stopping never raises
            pass

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=3)
        with self._lock:
            names = list(self._running)
        for name in names:
            self.stop(name)
        with self._lock:
            leftovers = list(self._launched)
        for running in leftovers:
            self._kill(running)

    # ---- reading -----------------------------------------------------------------------------------------------------

    def running(self, name: str) -> Running | None:
        with self._lock:
            return self._running.get(name)

    def endpoint(self, name: str) -> str | None:
        running = self.running(name)
        return running.endpoint if running else None

    def down_reason(self, name: str) -> str:
        return self._down.get(name, "")

    def log_path(self, name: str) -> Path:
        return self.logs_dir / f"{name}.log"

    def log_tail(self, name: str, lines: int = 200) -> list[str]:
        out: list[str] = []
        for path in (self.log_path(name).with_suffix(".log.1"), self.log_path(name)):
            if path.is_file():
                out += path.read_text(encoding="utf-8", errors="replace").splitlines()
        return out[-lines:]

    def _pump(self, running: Running) -> None:
        path = self.log_path(running.spec.name)
        hide = [running.spec.key, running.spec.key.split(".", 1)[-1], *running.spec.hide]
        stream = running.proc.stdout
        try:
            for raw in iter(stream.readline, b""):
                line = redact(raw.decode("utf-8", errors="replace").rstrip("\r\n"), hide)[:2000]
                stamp = time.strftime("%Y-%m-%d %H:%M:%S")
                try:
                    if path.is_file() and path.stat().st_size > LOG_BYTES:
                        path.replace(path.with_suffix(".log.1"))
                    with path.open("a", encoding="utf-8") as f:
                        f.write(f"{stamp} {line}\n")
                except OSError:
                    pass
        except (OSError, ValueError):
            pass

    # ---- keeping alive -----------------------------------------------------------------------------------------------

    def start_monitor(self, every_s: float = 5.0) -> None:
        if self._thread is not None:
            return

        def loop() -> None:
            while not self._stop.wait(every_s):
                try:
                    self.monitor_once()
                except Exception:  # noqa: BLE001 - the monitor never takes the runtime down
                    pass

        self._thread = threading.Thread(target=loop, name="cyclone-plugin-host", daemon=True)
        self._thread.start()

    def monitor_once(self, health: bool = True) -> None:
        with self._lock:
            items = list(self._running.items())
        for name, running in items:
            reason = ""
            if running.proc.poll() is not None:
                reason = f"The plugin stopped (exit code {running.proc.poll()})."
            elif health:
                status, _, _ = self._fetch("GET", running.endpoint + "/cyclone-plugin.json", timeout=3)
                if status == 200:
                    self._health_failures[name] = 0
                else:
                    self._health_failures[name] = self._health_failures.get(name, 0) + 1
                    if self._health_failures[name] >= HEALTH_FAILURES:
                        reason = "The plugin stopped answering."
            if reason:
                self._restart(name, running, reason)

    def _restart(self, name: str, running: Running, reason: str) -> None:
        now = self._clock()
        history = self._restarts.setdefault(name, collections.deque())
        while history and now - history[0] > RESTART_WINDOW_S:
            history.popleft()
        if len(history) >= RESTARTS_MAX:
            with self._lock:
                if self._running.get(name) is running:
                    self._running.pop(name)
                self._down[name] = f"{reason} It restarted {RESTARTS_MAX} times in 10 minutes, so Cyclone stopped it."
            self._kill(running)
            self._on_state(name, "crashed", self._down[name])
            return
        due = self._next_restart.get(name)
        if due is None:
            self._next_restart[name] = now + BACKOFF_S[min(len(history), len(BACKOFF_S) - 1)]
            self._on_state(name, "restarting", reason)
            return
        if now < due:
            return
        self._next_restart.pop(name, None)
        history.append(now)
        self._kill(running)
        try:
            fresh = self.launch(running.spec)
        except HostError as exc:
            # Count the failed start too; keep the dead record so the next pass tries again with a longer wait.
            self._on_state(name, "restarting", str(exc))
            return
        self.adopt(fresh)

