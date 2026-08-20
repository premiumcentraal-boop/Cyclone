from __future__ import annotations

from dataclasses import asdict
import re

from .client import ADBClient


def _prop(adb: ADBClient, name: str) -> str:
    return adb.shell("getprop", name).strip()


def collect_device_status(adb: ADBClient, requested_serial: str | None = None) -> dict:
    d = adb.select_device(requested_serial)
    size = adb.shell("wm", "size").strip()
    orientation_raw = adb.shell("dumpsys", "input").strip()
    m = re.search(r"SurfaceOrientation:\s*(\d+)", orientation_raw)
    orientation = int(m.group(1)) if m else None
    try:
        root = "uid=0" in adb.shell("su", "-c", "id", timeout=5)
    except Exception:
        root = False
    packages = adb.shell("pm", "list", "packages", "-3")
    cyclone_installed = any("cyclone" in line.lower() for line in packages.splitlines())
    return {
        **asdict(d),
        "manufacturer": _prop(adb, "ro.product.manufacturer"),
        "android_version": _prop(adb, "ro.build.version.release"),
        "sdk": _prop(adb, "ro.build.version.sdk"),
        "screen_resolution": size.split(":", 1)[-1].strip() if ":" in size else size,
        "orientation": orientation,
        "root_available": root,
        "cyclone_app_installed": cyclone_installed,
    }
