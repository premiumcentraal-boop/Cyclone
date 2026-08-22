from __future__ import annotations

import ctypes
from ctypes import wintypes
import os
import threading
import time

from cyclone_device_gateway.cli import main
from secure_gateway_token import save_connection

_STILL_ACTIVE = 259
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def _windows_kernel32():
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    return kernel32


def _parent_alive(pid: int) -> bool:
    if pid <= 0:
        return True
    if os.name == "nt":
        kernel32 = _windows_kernel32()
        handle = kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == _STILL_ACTIVE
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _watch_parent(pid: int) -> None:
    while True:
        time.sleep(2.0)
        if not _parent_alive(pid):
            # The Gateway is a child of the desktop product. It must never linger after the
            # Companion disappears, because a stale authenticated loopback process can otherwise
            # occupy the old port and make a later launch look healthy while rejecting its token.
            os._exit(0)


def _start_parent_watch() -> None:
    raw = os.getenv("CYCLONE_PC_PARENT_PID", "").strip()
    if not raw:
        return
    try:
        pid = int(raw)
    except ValueError:
        return
    threading.Thread(target=_watch_parent, args=(pid,), name="cyclone-parent-watch", daemon=True).start()


if __name__ == "__main__":
    token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
    url = os.getenv("CYCLONE_DEVICE_GATEWAY_URL", "http://127.0.0.1:8765").strip()
    if token:
        save_connection(token, url)
    _start_parent_watch()
    raise SystemExit(main())
