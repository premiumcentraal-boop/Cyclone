from __future__ import annotations

import ctypes
from ctypes import wintypes
import os
from pathlib import Path

CRYPTPROTECT_UI_FORBIDDEN = 0x1


class DATA_BLOB(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def token_path() -> Path:
    root = Path(os.getenv("LOCALAPPDATA") or Path.home()) / "Cyclone" / "pc-companion"
    root.mkdir(parents=True, exist_ok=True)
    return root / "gateway-token.dpapi"


def save_token(token: str) -> None:
    value = token.strip()
    if not value:
        return
    if os.name != "nt":
        # Development hosts use the environment only. Never create a plaintext fallback file.
        return
    encrypted = _protect(value.encode("utf-8"))
    path = token_path()
    temporary = path.with_suffix(".tmp")
    temporary.write_bytes(encrypted)
    temporary.replace(path)


def load_token() -> str | None:
    value = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
    if value:
        return value
    if os.name != "nt":
        return None
    path = token_path()
    if not path.is_file():
        return None
    try:
        return _unprotect(path.read_bytes()).decode("utf-8").strip() or None
    except Exception:
        return None


def _blob(data: bytes):
    buffer = ctypes.create_string_buffer(data)
    blob = DATA_BLOB(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    return buffer, blob


def _protect(data: bytes) -> bytes:
    _, source = _blob(data)
    output = DATA_BLOB()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptProtectData(
        ctypes.byref(source), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(output)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel32.LocalFree(output.pbData)


def _unprotect(data: bytes) -> bytes:
    _, source = _blob(data)
    output = DATA_BLOB()
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    if not crypt32.CryptUnprotectData(
        ctypes.byref(source), None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(output)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel32.LocalFree(output.pbData)
