"""Install the `cyclone` command once: a small cyclone.cmd on the user's PATH that starts the Cyclone One runtime.

Run by the Cyclone One installer (post-install hook). Only the current user's PATH is touched, never the machine's.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path, PureWindowsPath

UPDATE_EXIT_CODE = 10
SETUP_NAME = "Cyclone-Setup.exe"


def shim_text(runtime_exe: Path, updates_dir: Path) -> str:
    # cyclone.cmd is a Windows file whatever builds it: always Windows separators.
    runtime_exe = PureWindowsPath(str(runtime_exe))
    setup = PureWindowsPath(str(updates_dir)) / SETUP_NAME
    return "\r\n".join([
        "@echo off",
        "rem Cyclone terminal command, written by the Cyclone One installer. Type: cyclone",
        "setlocal",
        f'set "CYCLONE_RUNTIME={runtime_exe}"',
        'if not exist "%CYCLONE_RUNTIME%" (',
        "  echo Cyclone One is not installed where this command expects it. Reinstall Cyclone One, then open a new terminal.",
        "  exit /b 1",
        ")",
        '"%CYCLONE_RUNTIME%" terminal %*',
        'set "CYCLONE_EXIT=%ERRORLEVEL%"',
        f'if not "%CYCLONE_EXIT%"=="{UPDATE_EXIT_CODE}" exit /b %CYCLONE_EXIT%',
        # The runtime has exited, so the installer can replace it.
        "echo Installing the Cyclone update. Cyclone One closes while it installs...",
        f'start "" /wait "{setup}" /S',
        "if errorlevel 1 (",
        f'  echo The update did not install. Run "{setup}" yourself.',
        "  exit /b 1",
        ")",
        "echo Update installed.",
        '"%CYCLONE_RUNTIME%" terminal --no-update',
        "exit /b %ERRORLEVEL%",
        "",
    ])


def _norm(entry: str) -> str:
    return os.path.normcase(os.path.expandvars(entry.strip().strip('"')).rstrip("\\/"))


def path_with(current: str, directory: str) -> str | None:
    """PATH with `directory` appended, or None when it is already there."""
    entries = [e for e in current.split(";") if e.strip()]
    if any(_norm(e) == _norm(directory) for e in entries):
        return None
    return ";".join(entries + [directory])


def path_without(current: str, directory: str) -> str | None:
    entries = [e for e in current.split(";") if e.strip()]
    kept = [e for e in entries if _norm(e) != _norm(directory)]
    return None if len(kept) == len(entries) else ";".join(kept)


def default_bin_dir() -> Path:
    from ..tooling_seam import one_install_dir

    return one_install_dir() / "bin"


def install(runtime_exe: Path, bin_dir: Path | None = None, *, remove: bool = False) -> str:
    bin_dir = bin_dir or default_bin_dir()
    shim = bin_dir / "cyclone.cmd"
    if remove:
        shim.unlink(missing_ok=True)
        _update_user_path(lambda current: path_without(current, str(bin_dir)))
        return f"Removed the cyclone command ({shim})."
    bin_dir.mkdir(parents=True, exist_ok=True)
    shim.write_text(shim_text(runtime_exe, bin_dir.parent / "updates"), encoding="ascii", errors="replace")
    changed = _update_user_path(lambda current: path_with(current, str(bin_dir)))
    return f"Installed {shim}." + (" Open a new terminal and type: cyclone" if changed else "")


def _update_user_path(edit) -> bool:
    if os.name != "nt":
        return False
    import winreg

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment", 0, winreg.KEY_READ | winreg.KEY_WRITE) as key:
        try:
            current, kind = winreg.QueryValueEx(key, "Path")
        except FileNotFoundError:
            current, kind = "", winreg.REG_EXPAND_SZ
        updated = edit(str(current))
        if updated is None:
            return False
        winreg.SetValueEx(key, "Path", 0, kind if kind in (winreg.REG_SZ, winreg.REG_EXPAND_SZ) else winreg.REG_EXPAND_SZ, updated)
    _broadcast_environment_change()
    return True


def _broadcast_environment_change() -> None:
    """New terminals pick up the PATH change without signing out."""
    import ctypes
    from ctypes import wintypes

    result = wintypes.DWORD()
    ctypes.windll.user32.SendMessageTimeoutW(0xFFFF, 0x001A, 0, "Environment", 0x0002, 5000, ctypes.byref(result))


def runtime_executable() -> Path:
    return Path(sys.executable).resolve()
