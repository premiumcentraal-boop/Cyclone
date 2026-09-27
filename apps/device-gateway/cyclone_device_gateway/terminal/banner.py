"""What `cyclone` prints: who is running, which phones are here, what Glass is doing. Teal, compact, no secrets."""

from __future__ import annotations

import json
import os
import sys
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Callable

TEAL = (31, 182, 166)
DIM = (120, 140, 150)
WARN = (240, 180, 70)

LOGO = [
    r"   ___ _   _  ___ _     ___  _  _ ___ ",
    r"  / __| | | |/ __| |   / _ \| \| | __|",
    r" | (__| |_| | (__| |__| (_) | .` | _| ",
    r"  \___|\__, |\___|____|\___/|_|\_|___|",
    r"       |___/                          ",
]


@dataclass
class Overview:
    version: str
    update: str = ""
    runtime: str = ""
    address: str = ""
    phones: list[str] = field(default_factory=list)
    glass: str = ""


def enable_color() -> bool:
    if os.environ.get("NO_COLOR") or not getattr(sys.stdout, "isatty", lambda: False)():
        return False
    if os.name != "nt":
        return True
    try:
        import ctypes

        kernel32 = ctypes.windll.kernel32
        handle = kernel32.GetStdHandle(-11)
        mode = ctypes.c_uint32()
        if not kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            return False
        return bool(kernel32.SetConsoleMode(handle, mode.value | 0x0004))  # ENABLE_VIRTUAL_TERMINAL_PROCESSING
    except Exception:
        return False


def paint(text: str, rgb: tuple[int, int, int], color: bool, bold: bool = False) -> str:
    if not color:
        return text
    return f"\x1b[{'1;' if bold else ''}38;2;{rgb[0]};{rgb[1]};{rgb[2]}m{text}\x1b[0m"


def render(view: Overview, color: bool) -> list[str]:
    lines = [paint(row, TEAL, color, bold=True) for row in LOGO]
    lines.append(paint("  your phone, driven from this terminal", DIM, color))
    lines.append("")

    def row(label: str, value: str, rgb: tuple[int, int, int] = TEAL) -> None:
        if value:
            lines.append(f"  {paint(label.ljust(9), rgb, color, bold=True)} {value}")

    row("Cyclone", view.version + (f"  ·  {view.update}" if view.update else ""))
    row("Runtime", view.runtime + (f"  ·  {view.address}" if view.address else ""))
    if view.phones:
        row("Phones", view.phones[0])
        for phone in view.phones[1:]:
            lines.append(f"  {' ' * 9} {phone}")
    else:
        row("Phones", "none yet · connect over USB or pair in Glass → Devices", WARN)
    row("Glass", view.glass)
    lines.append("")
    lines.append(paint("  cyclone update · cyclone --browser · cyclone version · Ctrl+C to stop", DIM, color))
    return lines


def phone_lines(devices: list[dict[str, Any]]) -> list[str]:
    out = []
    for device in devices[:6]:
        if not isinstance(device, dict):
            continue
        name = str(device.get("name") or device.get("model") or "Android phone")[:40]
        state = str(device.get("connectionLabel") or device.get("state") or "")[:40]
        transport = str((device.get("transport") or {}).get("endpoint") or device.get("source") or "").upper()
        out.append(" · ".join(part for part in (name, state, transport) if part))
    return out


def fetch_phones(base_url: str, token: str, *, timeout: float = 1.5,
                 opener: Callable[..., Any] = urllib.request.urlopen) -> list[str]:
    request = urllib.request.Request(base_url.rstrip("/") + "/v1/fleet", headers={"Authorization": f"Bearer {token}"})
    try:
        with opener(request, timeout=timeout) as response:  # noqa: S310 - loopback gateway URL
            body = json.loads(response.read().decode("utf-8"))
    except Exception:
        return []
    devices = body.get("devices") if isinstance(body, dict) else None
    return phone_lines(devices if isinstance(devices, list) else [])
