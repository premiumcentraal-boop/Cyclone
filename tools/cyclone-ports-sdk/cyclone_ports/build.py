"""``cyclone-plugin-build``: turn a plugin folder into its checked release file (plan 50 §3). Used by the build Action
and the Windows publish smoke; plugin authors can run it themselves.

    cyclone-plugin-build --folder examples/logger --script examples/logger/plugin.py --out dist \\
        --test-settings '{"folder": "runs"}'

Steps: check the describing files; build one self-contained program with PyInstaller (or take ``--program``); start it
the way Cyclone does (managed, handshake on stdin) and run the conformance checks against it; pack the zip; print
``{"file", "sha256", "bytes"}``.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

from .package import MANAGED_ENV, REQUIRED_FILES, SCHEMA, TOML, PackageError, handshake_line, pack, parse_toml, \
    validate_package


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def pyinstaller(script: Path, name: str, manifest: Path, work: Path) -> Path:
    sep = ";" if os.name == "nt" else ":"
    subprocess.run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--onefile", "--clean", "--name", name,
                    "--distpath", str(work / "dist"), "--workpath", str(work / "build"), "--specpath", str(work),
                    "--add-data", f"{manifest}{sep}.", str(script)], check=True)
    built = work / "dist" / (name + (".exe" if os.name == "nt" else ""))
    if not built.is_file():
        raise SystemExit(f"PyInstaller didn't produce {built}")
    return built


def conformance(program: Path, data_dir: Path, settings: dict, timeout_s: float = 30) -> list:
    from .conformance import check_plugin

    port, key = _free_port(), "k1." + secrets.token_urlsafe(24)
    env = dict(os.environ, **{MANAGED_ENV: "1"})
    # The program's output goes nowhere and its whole process tree is stopped afterwards: a one-file program runs as a
    # starter plus a child, and a child left holding our stdout would keep the caller's pipe open forever.
    group = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
    proc = subprocess.Popen([str(program)], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            cwd=str(data_dir), env=env, text=True, **group)
    try:
        proc.stdin.write(handshake_line(port, key, str(data_dir), settings))
        proc.stdin.close()
        endpoint, deadline = f"http://127.0.0.1:{port}", time.monotonic() + timeout_s
        while True:
            try:
                urllib.request.urlopen(endpoint + "/cyclone-plugin.json", timeout=2).read()
                break
            except OSError:
                if proc.poll() is not None or time.monotonic() > deadline:
                    raise SystemExit(f"the plugin didn't answer on {endpoint} (exit code {proc.poll()})")
                time.sleep(0.3)
        return check_plugin(endpoint, key)
    finally:
        stop_tree(proc)


def stop_tree(proc: subprocess.Popen) -> None:
    """Stops a program and every process it started."""
    if os.name == "nt":
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        import signal
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            proc.terminate()
    try:
        proc.wait(10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(5)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cyclone-plugin-build", description=__doc__.splitlines()[0])
    parser.add_argument("--folder", required=True, help="holds cyclone-plugin.toml, cyclone-plugin.json, README.md, LICENSE")
    parser.add_argument("--script", help="the Python entry point to build with PyInstaller (local plugins)")
    parser.add_argument("--program", help="an already built program instead of --script")
    parser.add_argument("--out", default="dist")
    parser.add_argument("--test-settings", default="{}", help="JSON settings used for the conformance run")
    parser.add_argument("--tag", default="", help="the git tag; must be v<version> when given")
    args = parser.parse_args(argv)
    folder = Path(args.folder)
    package = parse_toml((folder / TOML).read_text(encoding="utf-8"))
    problems = validate_package(package)
    if args.tag and args.tag != f"v{package.get('version')}":
        problems.append(f"the tag {args.tag} doesn't match version {package.get('version')} (expected v{package.get('version')})")
    if problems:
        print(json.dumps({"ok": False, "problems": problems}, indent=2))
        return 1
    with tempfile.TemporaryDirectory(prefix="cyclone-plugin-") as tmp:
        work = Path(tmp)
        stage = work / "stage"
        stage.mkdir()
        for name in REQUIRED_FILES + ((SCHEMA,) if package.get("settings") else ()):
            shutil.copy(folder / name, stage / name)
        if package["kind"] == "local":
            if args.program:
                program = Path(args.program)
            elif args.script:
                program = pyinstaller(Path(args.script), package["name"], folder / "cyclone-plugin.json", work)
            else:
                parser.error("a local plugin needs --script or --program")
            entry = stage / package["local"]["entry"]
            entry.parent.mkdir(parents=True)
            shutil.copy(program, entry)
            entry.chmod(0o755)
            data_dir = work / "data"
            data_dir.mkdir()
            checks = conformance(entry, data_dir, json.loads(args.test_settings))
            failed = [c for c in checks if c.required and not c.ok]
            if failed:
                print(json.dumps({"ok": False, "problems": [f"conformance: {c.name} ({c.detail})" for c in failed]}, indent=2))
                return 1
        try:
            target = pack(stage, Path(args.out))
        except PackageError as exc:
            print(json.dumps({"ok": False, "problems": exc.problems}, indent=2))
            return 1
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(json.dumps({"ok": True, "file": str(target), "sha256": digest, "bytes": target.stat().st_size}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
