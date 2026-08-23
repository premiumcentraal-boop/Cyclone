from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from .fleet import DeviceSession

_PACKAGE = "com.cyclone.mobile"
_SAFE_NAME = re.compile(r"[^A-Za-z0-9_.-]+")


class DeviceDiagnosticRecorder:
    """Bounded, read-only Android monitor for a detected Cyclone phone.

    The recorder starts as soon as an authorized USB device enters the fleet. It never exposes a
    caller-supplied shell/ADB surface and never requires root/su. It watches the Cyclone process,
    keeps warning/error logcat for that PID, and writes fixed Android snapshots around process and
    pairing transitions so a physical-device crash is explainable after the fact.
    """

    POLL_SECONDS = 0.75
    SETTLE_SECONDS = 0.50
    MAX_LOG_BYTES = 2 * 1024 * 1024

    def __init__(self, session: "DeviceSession", runtime_root: Path | None = None):
        self.session = session
        root = runtime_root or Path(os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")).expanduser().resolve()
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime())
        safe_device = _safe_component(session.device_id)
        self.path = root / "diagnostics" / f"live-{safe_device}-{stamp}"
        self.timeline_path = self.path / "timeline.jsonl"
        self.logcat_path = self.path / "cyclone-process.logcat.txt"
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._logcat_process: subprocess.Popen | None = None
        self._logcat_file = None
        self._started_at_ms = 0
        self._last_stage = "not-started"
        self._latest_snapshot: str | None = None
        self._pid: str | None = None
        self._snapshot_counter = 0

    def start(self) -> None:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return
            self.path.mkdir(parents=True, exist_ok=True)
            self._started_at_ms = int(time.time() * 1000)
            self._stop.clear()
            self._thread = threading.Thread(target=self._watch_loop, name=f"cyclone-diag-{self.session.device_id[:8]}", daemon=True)
            self._thread.start()
        self.mark("usb.authorized.monitor_started", snapshot=True)

    def stop(self) -> None:
        self.mark("usb.monitor_stopping")
        self._stop.set()
        self._stop_logcat()
        thread = self._thread
        if thread and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=2.0)

    def mark(self, stage: str, *, snapshot: bool = False) -> str | None:
        normalized = _safe_stage(stage)
        with self._lock:
            self._last_stage = normalized
            self._append_timeline({
                "atEpochMs": int(time.time() * 1000),
                "stage": normalized,
                "pid": self._pid,
            })
        if snapshot:
            return self.capture_snapshot(normalized)
        return None

    def capture_snapshot(self, label: str) -> str | None:
        normalized = _safe_stage(label)
        try:
            snapshot = self._fixed_snapshot()
            with self._lock:
                self._snapshot_counter += 1
                name = f"snapshot-{self._snapshot_counter:03d}-{_safe_component(normalized)}.json"
                target = self.path / name
                target.write_text(json.dumps(snapshot, indent=2, sort_keys=True), encoding="utf-8")
                self._latest_snapshot = str(target)
                self._append_timeline({
                    "atEpochMs": int(time.time() * 1000),
                    "stage": "diagnostics.snapshot",
                    "label": normalized,
                    "path": str(target),
                    "pid": self._pid,
                })
                return str(target)
        except Exception as exc:
            with self._lock:
                self._append_timeline({
                    "atEpochMs": int(time.time() * 1000),
                    "stage": "diagnostics.snapshot_failed",
                    "label": normalized,
                    "error": f"{exc.__class__.__name__}: {str(exc)[:240]}",
                })
            return None

    def public_status(self) -> dict[str, Any]:
        thread = self._thread
        with self._lock:
            return {
                "active": bool(thread and thread.is_alive() and not self._stop.is_set()),
                "sessionPath": str(self.path),
                "timelinePath": str(self.timeline_path),
                "latestSnapshotPath": self._latest_snapshot,
                "lastStage": self._last_stage,
                "appPid": self._pid,
                "startedAtEpochMs": self._started_at_ms or None,
                "rootRequired": False,
                "mode": "ADB_READ_ONLY_PROCESS_MONITOR",
            }

    def _watch_loop(self) -> None:
        last_pid: str | None = None
        while not self._stop.is_set():
            current_pid = self._read_pid()
            if current_pid != last_pid:
                with self._lock:
                    self._pid = current_pid
                self._stop_logcat()
                if current_pid:
                    self.mark("android.cyclone_process_started")
                    self._start_logcat(current_pid)
                    self.capture_snapshot("android.process_started")
                elif last_pid:
                    self.mark("android.cyclone_process_disappeared")
                    self.capture_snapshot("android.process_gone_immediate")
                    if not self._stop.wait(self.SETTLE_SECONDS):
                        self.capture_snapshot("android.process_gone_settled")
                last_pid = current_pid
            self._stop.wait(self.POLL_SECONDS)

    def _read_pid(self) -> str | None:
        try:
            value = self.session.adb.shell("pidof", _PACKAGE, timeout=3).strip().split()
            return value[0] if value else None
        except Exception:
            return None

    def _start_logcat(self, pid: str) -> None:
        try:
            self.path.mkdir(parents=True, exist_ok=True)
            if self.logcat_path.exists() and self.logcat_path.stat().st_size > self.MAX_LOG_BYTES:
                previous = self.logcat_path.with_suffix(self.logcat_path.suffix + ".previous")
                try:
                    previous.unlink(missing_ok=True)
                except Exception:
                    pass
                try:
                    self.logcat_path.replace(previous)
                except Exception:
                    pass
            handle = self.logcat_path.open("ab", buffering=0)
            process = self.session.adb.start_process(
                ["logcat", f"--pid={pid}", "-v", "threadtime", "*:W"],
                stdout=handle,
            )
            with self._lock:
                self._logcat_file = handle
                self._logcat_process = process
            self.mark("android.logcat_attached")
        except Exception as exc:
            try:
                handle.close()  # type: ignore[name-defined]
            except Exception:
                pass
            self._append_timeline({
                "atEpochMs": int(time.time() * 1000),
                "stage": "android.logcat_attach_failed",
                "error": f"{exc.__class__.__name__}: {str(exc)[:240]}",
            })

    def _stop_logcat(self) -> None:
        with self._lock:
            process = self._logcat_process
            handle = self._logcat_file
            self._logcat_process = None
            self._logcat_file = None
        if process is not None:
            try:
                process.terminate()
                process.wait(timeout=1.0)
            except Exception:
                try:
                    process.kill()
                except Exception:
                    pass
        if handle is not None:
            try:
                handle.close()
            except Exception:
                pass

    def _fixed_snapshot(self) -> dict[str, Any]:
        adb = self.session.adb

        def capture(action) -> str:
            try:
                value = str(action()).replace("\x00", "")
                return value[-64_000:]
            except Exception as exc:
                return f"<unavailable: {exc.__class__.__Name__}: {str(exc)[:240]}>"

        crash = adb.collect_cyclone_crash_diagnostics() if hasattr(adb, "collect_cyclone_crash_diagnostics") else {}
        return {
            "capturedAtEpochMs": int(time.time() * 1000),
            "deviceId": self.session.device_id,
            "adbState": self.session.adb_device.state,
            "model": self.session.adb_device.model,
            "product": self.session.adb_device.product,
            "processPid": self._read_pid(),
            "lastMonitorStage": self._last_stage,
            "androidRelease": capture(lambda: adb.shell("getprop", "ro.build.version.release", timeout=3).strip()),
            "androidSdk": capture(lambda: adb.shell("getprop", "ro.build.version.sdk", timeout=3).strip()),
            "bootReason": capture(lambda: adb.shell("getprop", "ro.boot.bootreason", timeout=3).strip()),
            "uptime": capture(lambda: adb.shell("cat", "/proc/uptime", timeout=3).strip()),
            "packageState": capture(lambda: adb.shell("dumpsys", "package", _PACKAGE, timeout=8)),
            "processMemory": capture(lambda: adb.shell("dumpsys", "meminfo", _PACKAGE, timeout=8)),
            "accessibilityState": capture(lambda: adb.shell("dumpsys", "accessibility", timeout=8)),
            "crash": crash,
            "privacy": "Fixed read-only ADB diagnostics only. No root/su, arbitrary shell, pairing code, credential, clipboard content, password, OTP, or typed value is intentionally recorded.",
        }

    def _append_timeline(self, item: dict[str, Any]) -> None:
        try:
            self.path.mkdir(parents=True, exist_ok=True)
            with self.timeline_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(item, sort_keys=True) + "\n")
        except Exception:
            pass


def _safe_component(value: str) -> str:
    return _SAFE_NAME.sub("-", value).strip("-._")[:96] or "unknown"


def _safe_stage(value: str) -> str:
    return _SAFE_NAME.sub("_", value).strip("._-")[:120] or "unknown"
