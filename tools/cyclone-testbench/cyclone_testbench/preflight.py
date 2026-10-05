"""Preflight: get the phone into a state where a run can only fail because of Cyclone (alpha 108).

`cyclone-testbench doctor --fix` runs these over ADB before a round. Each check says what it found and, where it is
safe, puts it right. It never uninstalls, clears an app's data, resets anything or touches the owner's accounts, and it
does not unlock a phone that has a PIN, pattern or password: a locked phone stops the round and the owner is told.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

CYCLONE = "com.cyclone.mobile"
MIN_BATTERY = 30
MAX_TEMP_C = 42.0

Shell = Callable[[list[str]], str]


@dataclass
class Check:
    name: str
    ok: bool
    detail: str
    fixed: bool = False
    blocking: bool = True

    def line(self) -> str:
        mark = "✓" if self.ok else ("✗" if self.blocking else "•")
        return f"{mark} {self.name}: {self.detail}" + (" (fixed)" if self.fixed else "")


def find_adb() -> str | None:
    """Cyclone's own adb first (it is the one the gateway uses), then PATH."""
    local = os.environ.get("LOCALAPPDATA")
    if local:
        bundled = Path(local) / "Cyclone One" / "android-platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if bundled.is_file():
            return str(bundled)
    return shutil.which("adb")


def adb_shell(adb: str, serial: str | None) -> Shell:
    def run(args: list[str]) -> str:
        cmd = [adb] + (["-s", serial] if serial else []) + args
        done = subprocess.run(cmd, capture_output=True, text=True, timeout=20, encoding="utf-8", errors="replace")
        return (done.stdout or "") + (done.stderr or "")
    return run


def devices(run: Shell) -> list[str]:
    out = run(["devices"])
    return [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]


def devices_restarting(run: Shell) -> tuple[list[str], bool]:
    """Alpha 109: a Cyclone update can leave a stale ADB server that sees no phone; restart it once."""
    found = devices(run)
    if found:
        return found, False
    run(["kill-server"])
    run(["start-server"])
    return devices(run), True


def preflight(run: Shell, *, fix: bool, apps: set[str] | None = None) -> list[Check]:
    """`run` executes one adb command (arguments after `adb -s SERIAL`) and returns its output."""
    checks: list[Check] = []
    sh = lambda *a: run(["shell", *a])  # noqa: E731 - a short alias keeps the checks readable

    if fix:
        sh("input", "keyevent", "KEYCODE_WAKEUP")
    window = sh("dumpsys", "window")
    locked = bool(re.search(r"(mShowingLockscreen|isKeyguardShowing|mDreamingLockscreen)=true", window)) or \
        bool(re.search(r"isStatusBarKeyguard=true", window))
    if locked and fix:
        sh("wm", "dismiss-keyguard")
        window = sh("dumpsys", "window")
        locked = bool(re.search(r"(mShowingLockscreen|isKeyguardShowing|mDreamingLockscreen)=true", window))
        checks.append(Check("unlocked", not locked, "still locked: turn the screen lock off while testing, or unlock it" if locked
                            else "was on the lock screen, dismissed it", fixed=not locked))
    else:
        checks.append(Check("unlocked", not locked, "on the lock screen: unlock the phone" if locked else "yes"))

    stay = sh("settings", "get", "global", "stay_on_while_plugged_in").strip()
    if stay not in {"3", "7", "15"} and fix:
        sh("settings", "put", "global", "stay_on_while_plugged_in", "3")
        stay2 = sh("settings", "get", "global", "stay_on_while_plugged_in").strip()
        checks.append(Check("stays awake on power", stay2 in {"3", "7", "15"}, f"was {stay or 'off'}, now {stay2}", fixed=stay2 in {"3", "7", "15"}))
    else:
        checks.append(Check("stays awake on power", stay in {"3", "7", "15"}, "yes" if stay in {"3", "7", "15"}
                            else "no (run doctor --fix, or Developer options > Stay awake)"))

    idle = sh("dumpsys", "deviceidle", "whitelist")
    exempt = CYCLONE in idle
    if not exempt and fix:
        sh("dumpsys", "deviceidle", "whitelist", f"+{CYCLONE}")
        exempt = CYCLONE in sh("dumpsys", "deviceidle", "whitelist")
        checks.append(Check("Cyclone not put to sleep", exempt, "added to the battery exemption list" if exempt else "could not add it",
                            fixed=exempt))
    else:
        checks.append(Check("Cyclone not put to sleep", exempt, "yes" if exempt else "no: set Cyclone's battery use to Unrestricted"))

    services = sh("settings", "get", "secure", "enabled_accessibility_services")
    checks.append(Check("Cyclone accessibility on", CYCLONE in services,
                        "yes" if CYCLONE in services else "off: turn on Cyclone in Settings > Accessibility"))

    battery = sh("dumpsys", "battery")
    level = re.search(r"level:\s*(\d+)", battery)
    temp = re.search(r"temperature:\s*(\d+)", battery)
    pct = int(level.group(1)) if level else None
    celsius = int(temp.group(1)) / 10 if temp else None
    checks.append(Check("battery", pct is None or pct >= MIN_BATTERY, f"{pct}%" if pct is not None else "unknown"))
    ac = re.search(r"AC powered:\s*(true|false)", battery)
    if ac and ac.group(1) == "false":
        # Alpha 109: over a PC's USB port the Pixel lost 3% in 40 minutes of testing; long runs need a wall charger.
        checks.append(Check("charger", False, "charging over USB only: use a wall charger or a powered hub for long runs",
                            blocking=False))
    checks.append(Check("temperature", celsius is None or celsius <= MAX_TEMP_C,
                        f"{celsius:.1f} °C" if celsius is not None else "unknown"))

    if apps:
        listed = {line.split(":", 1)[1].strip() for line in sh("pm", "list", "packages").splitlines() if line.startswith("package:")}
        missing = sorted(apps - listed)
        checks.append(Check("mission apps installed", not missing, "all" if not missing else "missing " + ", ".join(missing)
                            + " (their missions are skipped)", blocking=False))
    return checks
