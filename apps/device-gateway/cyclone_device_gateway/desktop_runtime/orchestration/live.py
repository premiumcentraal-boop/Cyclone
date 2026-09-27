"""Project real ADB sessions into the fleet registry.

Online, offline, and lock state come from DeviceFleetManager and the device's
own dumpsys output. Nothing here invents a device, a battery, or a mission.
"""

from __future__ import annotations

import queue
import re
import threading
from typing import Any, Callable

from ..fleet import DeviceFleetManager
from ..models import DesktopRuntimeError, DeviceFleetState
from .controller import FleetController
from .power import ScreenState

_LOCK = re.compile(
    r"(mDreamingLockscreen=true|isStatusBarKeyguard=true|mShowingLockscreen=true|KeyguardShowing=true)"
)
_AWAKE = re.compile(r"(mInteractive=true|mWakefulness=Awake|Display Power: state=ON)", re.IGNORECASE)
_LEVEL = re.compile(r"^\s*level:\s*(\d+)\s*$", re.MULTILINE)
_BOOT = re.compile(r"^[0-9a-fA-F-]{8,64}$")
_RESUMED = re.compile(r"\b([a-zA-Z][\w.]*)/[\w.$]+")
_INTERESTING = frozenset({
    "DEVICE_ADDED",
    "DEVICE_REMOVED",
    "STATE_CHANGED",
    "SCREEN_STATE_CHANGED",
    "PAIRING_CHANGED",
})


class FleetLiveSync:
    """One subscriber on the existing fleet event broker. Not a second device scanner."""

    def __init__(
        self,
        fleet: DeviceFleetManager,
        orchestration: FleetController,
        *,
        trust_state: Callable[[str], str] | None = None,
    ):
        self.fleet = fleet
        self.orchestration = orchestration
        self._trust_state = trust_state
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._seen_online: set[str] = set()
        broker = getattr(fleet, "events", None)
        self._subscribed = broker is not None and hasattr(broker, "subscribe")
        self._queue: queue.Queue = broker.subscribe() if self._subscribed else queue.Queue()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        for device_id in self._current_ids():
            self.project(device_id)
        self._thread = threading.Thread(target=self._loop, name="cyclone-fleet-live-sync", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=2.0)
        self._thread = None
        if self._subscribed:
            try:
                self.fleet.events.unsubscribe(self._queue)
            except Exception:
                pass
            self._subscribed = False

    def drain(self, limit: int = 64) -> int:
        handled = 0
        while handled < limit and self.ingest_once(0):
            handled += 1
        return handled

    def ingest_once(self, timeout: float = 0.05) -> bool:
        try:
            event = self._queue.get(timeout=timeout)
        except queue.Empty:
            return False
        self._handle(event)
        return True

    def project(self, device_id: str) -> None:
        if not device_id.startswith("dev_"):
            return
        try:
            session = self.fleet.get(device_id)
        except DesktopRuntimeError:
            self._seen_online.discard(device_id)
            self.orchestration.note_disconnect(device_id)
            return
        adb_state = str(getattr(session.adb_device, "state", "") or "")
        online = session.state != DeviceFleetState.DISCONNECTED and adb_state == "device"
        if not online:
            self._seen_online.discard(device_id)
            self.orchestration.apply_transport_state(device_id, online=False, serial_suffix=_suffix(session.serial))
            return
        meta: dict[str, Any] = {}
        if device_id not in self._seen_online:
            meta = _read_metadata(session.adb)
            self._seen_online.add(device_id)
        trusted, revoked = self._trust_flags(session)
        screen = self.probe_screen(device_id)
        self.orchestration.apply_transport_state(
            device_id,
            online=True,
            trusted=trusted,
            revoked=revoked,
            model=meta.get("model") or getattr(session.adb_device, "model", None),
            manufacturer=meta.get("manufacturer"),
            serial_suffix=_suffix(session.serial),
            battery=meta.get("battery"),
            current_app=meta.get("current_app"),
            boot_id=meta.get("boot_id"),
            screen_state=screen.value,
        )

    def probe_screen(self, device_id: str) -> ScreenState:
        """Read keyguard/power from this device. Never sends a PIN or a dismiss gesture."""
        try:
            session = self.fleet.get(device_id)
        except DesktopRuntimeError:
            return ScreenState.UNKNOWN
        if session.state == DeviceFleetState.DISCONNECTED:
            return ScreenState.UNKNOWN
        adb = session.adb
        window = _shell(adb, ("dumpsys", "window"))
        power = _shell(adb, ("dumpsys", "power"))
        if not window and not power:
            return ScreenState.UNKNOWN
        if _LOCK.search(window):
            return ScreenState.LOCKED
        if power and not _AWAKE.search(power):
            return ScreenState.ASLEEP
        if _AWAKE.search(power):
            return ScreenState.AWAKE
        return ScreenState.UNKNOWN

    def wake_display(self, device_id: str) -> None:
        """Power-button equivalent only. Does not dismiss a lock screen."""
        session = self.fleet.get(device_id)
        session.adb.shell("input", "keyevent", "KEYCODE_WAKEUP", timeout=4)

    def _trust_flags(self, session: Any) -> tuple[bool, bool]:
        state = "UNPAIRED"
        if self._trust_state is not None:
            try:
                state = str(self._trust_state(session.device_id) or "UNPAIRED")
            except Exception:
                state = "UNPAIRED"
        if state == "REVOKED":
            return False, True
        if state == "TRUSTED" or bool(getattr(session, "credential", None)):
            return True, False
        return False, False

    def _current_ids(self) -> list[str]:
        try:
            public = self.fleet.list_public()
        except Exception:
            return []
        return [str(item.get("deviceId")) for item in public if isinstance(item, dict) and item.get("deviceId")]

    def _loop(self) -> None:
        while not self._stop.is_set():
            self.ingest_once(0.5)

    def _handle(self, event: dict[str, Any]) -> None:
        if not isinstance(event, dict):
            return
        if str(event.get("event") or "") not in _INTERESTING:
            return
        device_id = str(event.get("deviceId") or "")
        if device_id.startswith("dev_"):
            self.project(device_id)


def _suffix(serial: str) -> str | None:
    text = str(serial or "")
    if len(text) < 4:
        return text or None
    return text[-4:]


def _shell(adb: Any, args: tuple[str, ...]) -> str:
    try:
        value = adb.shell(*args, timeout=4)
    except Exception:
        return ""
    return value if isinstance(value, str) else ""


def _read_metadata(adb: Any) -> dict[str, Any]:
    manufacturer = _prop(adb, "ro.product.manufacturer")
    model = _prop(adb, "ro.product.model")
    battery = _battery(adb)
    current_app = _resumed_package(adb)
    boot_id = _boot_id(adb)
    meta: dict[str, Any] = {}
    if manufacturer:
        meta["manufacturer"] = manufacturer
    if model:
        meta["model"] = model
    if battery is not None:
        meta["battery"] = battery
    if current_app:
        meta["current_app"] = current_app
    if boot_id:
        meta["boot_id"] = boot_id
    return meta


def _prop(adb: Any, name: str) -> str | None:
    value = _shell(adb, ("getprop", name)).strip()
    if not value or value == "null" or len(value) > 80:
        return None
    return value


def _battery(adb: Any) -> int | None:
    match = _LEVEL.search(_shell(adb, ("dumpsys", "battery")))
    if not match:
        return None
    level = int(match.group(1))
    if level < 0 or level > 100:
        return None
    return level


def _resumed_package(adb: Any) -> str | None:
    text = _shell(adb, ("dumpsys", "activity", "activities"))
    for line in text.splitlines():
        if "mResumedActivity" not in line and "topResumedActivity" not in line:
            continue
        match = _RESUMED.search(line)
        if match:
            return match.group(1)[:120]
    return None


def _boot_id(adb: Any) -> str | None:
    value = _shell(adb, ("cat", "/proc/sys/kernel/random/boot_id")).strip()
    if _BOOT.fullmatch(value):
        return value
    return None
