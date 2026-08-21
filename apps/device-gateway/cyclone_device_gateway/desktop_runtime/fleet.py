from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
import hashlib
import re
import secrets
import threading
from typing import Any, Callable

from ..adb.client import ADBClient, ADBDevice, ADBError
from ..adb.device import CYCLONE_PACKAGE
from ..cyclone_bridge.client import (
    BridgeDisconnectedError,
    BridgeOperationError,
    BridgeProtocolError,
    CycloneBridgeClient,
)
from .events import FleetEventBroker
from .models import (
    DesktopRuntimeError,
    DeviceFleetState,
    FleetEventType,
    MAX_FLEET_DEVICES,
    RuntimeErrorCode,
    deterministic_device_id,
    now_ms,
)

_SIZE_RE = re.compile(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", re.IGNORECASE)


@dataclass
class RememberedSession:
    device_id: str
    credential: str | None = None
    local_port: int | None = None


@dataclass
class DeviceSession:
    device_id: str
    serial: str
    adb_device: ADBDevice
    adb: ADBClient
    local_port: int
    usb_session_id: str
    state: DeviceFleetState
    credential: str | None = None
    screen_awake: bool = True
    display_width: int | None = None
    display_height: int | None = None
    last_seen_ms: int = field(default_factory=now_ms)
    last_safe_error: str | None = None
    pending_pairing: Any = None
    video: Any = None

    def public(self) -> dict[str, Any]:
        suffix = self.serial[-4:] if len(self.serial) >= 4 else self.serial
        model = self.adb_device.model or self.adb_device.device or "Android phone"
        width = self.display_width or 1080
        height = self.display_height or 2400
        paired = self.credential is not None
        state = self.state.value
        connection_label = {
            DeviceFleetState.READY: "Ready",
            DeviceFleetState.SLEEPING: "Sleeping",
            DeviceFleetState.UNPAIRED: "Not paired",
            DeviceFleetState.PAIRING: "Pairing",
            DeviceFleetState.UNAUTHORIZED: "Authorize USB debugging",
            DeviceFleetState.ATTENTION: "Needs attention",
            DeviceFleetState.DISCONNECTED: "Reconnecting",
        }.get(self.state, state.replace("_", " ").title())
        return {
            # Canonical Desktop V1 names.
            "deviceId": self.device_id,
            "id": self.device_id,
            "state": state,
            "name": model,
            "model": self.adb_device.model,
            "manufacturer": None,
            "product": self.adb_device.product,
            "device": self.adb_device.device,
            "serialSuffix": suffix,
            "paired": paired,
            "pairing": self.pending_pairing is not None,
            "screen": "AWAKE" if self.screen_awake else "SLEEPING",
            "screenState": "AWAKE" if self.screen_awake else "SLEEPING",
            "display": {"width": width, "height": height},
            "displayWidth": width,
            "displayHeight": height,
            "lastSeenMs": self.last_seen_ms,
            "lastSeenEpochMs": self.last_seen_ms,
            "lastSafeError": self.last_safe_error,
            "connectionLabel": connection_label,
            # The V1 release uses the authenticated binary image WebSocket for both profiles.
            # Experimental AVC can be enabled by the backend later without changing this UI contract.
            "video": {
                "mode": "SCREENSHOT",
                "width": width,
                "height": height,
                "rotationDegrees": 0,
                "codec": "image/jpeg",
            },
            "capabilities": {
                "manualControl": paired,
                "keyboard": paired,
                "clipboard": paired,
                "clipboardSync": False,
                "reconnect": True,
                "clipboardMode": "PC_TO_PHONE" if paired else "PAIRING_REQUIRED",
                "video": ["thumbnail", "focus"],
            },
        }

    def bridge(self, token: str | None = None, *, auto_forward: bool = False) -> CycloneBridgeClient:
        return CycloneBridgeClient(
            host="127.0.0.1",
            port=self.local_port,
            token=self.credential if token is None else token,
            adb=self.adb,
            auto_forward=auto_forward,
        )


class DeviceFleetManager:
    """Bounded, failure-isolated inventory of USB-connected Android devices."""

    def __init__(
        self,
        *,
        adb_path: str = "adb",
        inventory_adb: ADBClient | None = None,
        adb_factory: Callable[[str], ADBClient] | None = None,
        poll_seconds: float = 1.0,
        max_devices: int = MAX_FLEET_DEVICES,
        max_workers: int = 8,
        event_broker: FleetEventBroker | None = None,
    ):
        self.adb_path = adb_path
        self.inventory_adb = inventory_adb or ADBClient(adb_path, None)
        self.adb_factory = adb_factory or (lambda serial: ADBClient(adb_path, serial))
        self.poll_seconds = max(0.2, min(float(poll_seconds), 10.0))
        self.max_devices = max(1, min(int(max_devices), 32))
        self.max_workers = max(1, min(int(max_workers), self.max_devices, 8))
        self.events = event_broker or FleetEventBroker()
        self._lock = threading.RLock()
        self._sessions: dict[str, DeviceSession] = {}
        self._serial_to_device: dict[str, str] = {}
        self._remembered: dict[str, RememberedSession] = {}
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._video_factory: Callable[[DeviceSession], Any] | None = None

    def set_video_factory(self, factory: Callable[[DeviceSession], Any]) -> None:
        self._video_factory = factory
        with self._lock:
            for session in self._sessions.values():
                if session.video is None:
                    session.video = factory(session)

    def start(self) -> None:
        with self._lock:
            if self._thread and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(target=self._monitor_loop, name="cyclone-device-fleet", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread and thread.is_alive():
            thread.join(timeout=3.0)
        with self._lock:
            sessions = tuple(self._sessions.values())
        for session in sessions:
            self._cleanup_session(session)

    def _monitor_loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.refresh_once()
            except Exception:
                pass
            self._stop.wait(self.poll_seconds)

    def list_public(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._sessions[key].public() for key in sorted(self._sessions)]

    def get(self, device_id: str) -> DeviceSession:
        with self._lock:
            session = self._sessions.get(device_id)
        if session is None:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "Device is not connected.", retryable=True)
        return session

    def refresh_once(self) -> list[dict[str, Any]]:
        try:
            devices = self.inventory_adb.devices()
        except ADBError as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, str(exc), retryable=True) from exc

        devices = devices[: self.max_devices]
        seen_serials = {d.serial for d in devices}
        with self._lock:
            known_serials = set(self._serial_to_device)
        for serial in known_serials - seen_serials:
            self._remove_serial(serial)

        refresh_targets: list[DeviceSession] = []
        for device in devices:
            session = self._upsert(device)
            if device.state == "unauthorized":
                self._set_state(session, DeviceFleetState.UNAUTHORIZED, "Authorize USB debugging on the phone.")
            elif device.state != "device":
                self._set_state(session, DeviceFleetState.ATTENTION, f"ADB state is {device.state}.")
            else:
                refresh_targets.append(session)

        if refresh_targets:
            with ThreadPoolExecutor(max_workers=self.max_workers, thread_name_prefix="cyclone-fleet") as pool:
                futures = {pool.submit(self._refresh_authorized, session): session for session in refresh_targets}
                for future in as_completed(futures):
                    session = futures[future]
                    try:
                        future.result()
                    except Exception:
                        self._set_state(session, DeviceFleetState.ATTENTION, "Device health check failed safely.")
        return self.list_public()

    def _upsert(self, device: ADBDevice) -> DeviceSession:
        with self._lock:
            existing_id = self._serial_to_device.get(device.serial)
            if existing_id and existing_id in self._sessions:
                session = self._sessions[existing_id]
                session.adb_device = device
                session.last_seen_ms = now_ms()
                return session

            remembered = self._remembered.get(device.serial)
            device_id = remembered.device_id if remembered else deterministic_device_id(device.serial)
            local_port = remembered.local_port if remembered and remembered.local_port is not None else self._allocate_port(device_id)
            session = DeviceSession(
                device_id=device_id,
                serial=device.serial,
                adb_device=device,
                adb=self.adb_factory(device.serial),
                local_port=local_port,
                usb_session_id=secrets.token_urlsafe(24),
                state=DeviceFleetState.UNAUTHORIZED if device.state == "unauthorized" else DeviceFleetState.UNPAIRED,
                credential=remembered.credential if remembered else None,
            )
            if self._video_factory:
                session.video = self._video_factory(session)
            self._sessions[device_id] = session
            self._serial_to_device[device.serial] = device_id
            self._remembered[device.serial] = RememberedSession(device_id, session.credential, local_port)
        self.events.publish(FleetEventType.DEVICE_ADDED, device_id, device=session.public())
        return session

    def _allocate_port(self, device_id: str) -> int:
        seed = int(hashlib.sha256(device_id.encode()).hexdigest()[:8], 16)
        base, span = 18000, 1000
        used = {s.local_port for s in self._sessions.values()}
        used.update(r.local_port for r in self._remembered.values() if r.local_port is not None)
        for offset in range(span):
            port = base + ((seed + offset) % span)
            if port not in used:
                return port
        raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "No free Cyclone device bridge port is available.")

    def _remove_serial(self, serial: str) -> None:
        with self._lock:
            device_id = self._serial_to_device.pop(serial, None)
            session = self._sessions.pop(device_id, None) if device_id else None
        if session is None:
            return
        self._remembered[serial] = RememberedSession(session.device_id, session.credential, session.local_port)
        self._cleanup_session(session)
        self.events.publish(FleetEventType.DEVICE_REMOVED, session.device_id, state=DeviceFleetState.DISCONNECTED.value)

    def _cleanup_session(self, session: DeviceSession) -> None:
        if session.video is not None:
            try:
                session.video.stop_all()
            except Exception:
                pass
        try:
            if hasattr(session.adb, "remove_forward"):
                session.adb.remove_forward(session.local_port)
        except Exception:
            pass

    def _refresh_authorized(self, session: DeviceSession) -> None:
        try:
            session.adb.ensure_bridge_forward(session.local_port)
            package_present = self._package_present(session)
            if not package_present:
                self._set_state(session, DeviceFleetState.ATTENTION, "Install the Cyclone mobile app on this phone.")
                return
            session.screen_awake = self._screen_awake(session)
            self._refresh_display(session)
            session.last_seen_ms = now_ms()
            target = DeviceFleetState.SLEEPING if not session.screen_awake and session.credential else (
                DeviceFleetState.READY if session.credential else DeviceFleetState.UNPAIRED
            )
            self._set_state(session, target, None)
        except Exception:
            self._set_state(session, DeviceFleetState.ATTENTION, "Cyclone USB bridge is not ready.")

    def _package_present(self, session: DeviceSession) -> bool:
        try:
            output = session.adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=4)
            return "package:" in output
        except Exception:
            return False

    def _screen_awake(self, session: DeviceSession) -> bool:
        try:
            output = session.adb.shell("dumpsys", "power", timeout=4).lower()
            return "minteractive=true" in output or "wakefulness=awake" in output or "display power: state=on" in output
        except Exception:
            return True

    def _refresh_display(self, session: DeviceSession) -> None:
        try:
            output = session.adb.shell("wm", "size", timeout=4)
            matches = _SIZE_RE.findall(output)
            if matches:
                width, height = matches[-1]
                session.display_width, session.display_height = int(width), int(height)
        except Exception:
            pass

    def remember_credential(self, session: DeviceSession, credential: str | None) -> None:
        with self._lock:
            session.credential = credential
            remembered = self._remembered.setdefault(session.serial, RememberedSession(session.device_id, None, session.local_port))
            remembered.credential = credential
            remembered.local_port = session.local_port

    def set_pairing(self, session: DeviceSession, pending: Any) -> None:
        with self._lock:
            session.pending_pairing = pending
        if pending is not None:
            self._set_state(session, DeviceFleetState.PAIRING, None)
        elif session.credential:
            self._set_state(session, DeviceFleetState.SLEEPING if not session.screen_awake else DeviceFleetState.READY, None)
        else:
            self._set_state(session, DeviceFleetState.UNPAIRED, None)
        self.events.publish(FleetEventType.PAIRING_CHANGED, session.device_id, pairing=pending is not None)

    def _set_state(self, session: DeviceSession, state: DeviceFleetState, error: str | None) -> None:
        changed = session.state != state
        session.state = state
        session.last_safe_error = error
        if changed:
            self.events.publish(FleetEventType.STATE_CHANGED, session.device_id, state=state.value, device=session.public())
