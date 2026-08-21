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
        return {
            "deviceId": self.device_id,
            "state": self.state.value,
            "model": self.adb_device.model,
            "product": self.adb_device.product,
            "device": self.adb_device.device,
            "serialSuffix": suffix,
            "paired": self.credential is not None,
            "pairing": self.pending_pairing is not None,
            "screen": "AWAKE" if self.screen_awake else "SLEEPING",
            "display": (
                {"width": self.display_width, "height": self.display_height}
                if self.display_width and self.display_height
                else None
            ),
            "lastSeenMs": self.last_seen_ms,
            "lastSafeError": self.last_safe_error,
            "capabilities": {
                "manualControl": self.credential is not None,
                "clipboard": "PC_TO_PHONE" if self.credential is not None else "PAIRING_REQUIRED",
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
            else:
                session.adb.run(["forward", "--remove", f"tcp:{session.local_port}"])
        except Exception:
            pass

    def _refresh_authorized(self, session: DeviceSession) -> None:
        session.last_seen_ms = now_ms()
        try:
            package_path = session.adb.shell("pm", "path", CYCLONE_PACKAGE, timeout=5).strip()
        except ADBError:
            self._set_state(session, DeviceFleetState.ATTENTION, "Selected phone disconnected during Cyclone package check.")
            return
        if not package_path.startswith("package:"):
            self._set_state(session, DeviceFleetState.ATTENTION, "Cyclone is not installed on this phone.")
            return
        try:
            session.adb.ensure_bridge_forward(session.local_port)
        except ADBError:
            self._set_state(session, DeviceFleetState.ATTENTION, "Could not create the isolated Cyclone USB forward.")
            return

        awake = self._screen_awake(session)
        self._set_screen_state(session, awake)
        self._refresh_display(session)
        if session.pending_pairing is not None:
            self._set_state(session, DeviceFleetState.PAIRING, None)
            return
        if session.credential is None:
            self._set_state(session, DeviceFleetState.UNPAIRED, None)
            return

        try:
            status = session.bridge().request("bridge.status", {})
        except BridgeOperationError as exc:
            if exc.code == "AUTH_REJECTED":
                session.credential = None
                self._remembered[session.serial].credential = None
                self._set_state(session, DeviceFleetState.UNPAIRED, "Pairing credential was rotated or revoked on the phone.")
            else:
                self._set_state(session, DeviceFleetState.ATTENTION, f"Android Gateway returned {exc.code}.")
            return
        except (BridgeDisconnectedError, BridgeProtocolError):
            self._set_state(session, DeviceFleetState.ATTENTION, "Cyclone Android Gateway is not reachable.")
            return

        ready = bool(
            isinstance(status, dict)
            and status.get("gatewayEnabled") is True
            and status.get("socketListening") is True
            and status.get("accessibilityConnected") is True
        )
        if not ready:
            self._set_state(session, DeviceFleetState.ATTENTION, "Cyclone Gateway or Accessibility needs attention.")
        elif not awake:
            self._set_state(session, DeviceFleetState.SLEEPING, None)
        else:
            self._set_state(session, DeviceFleetState.READY, None)

    def _screen_awake(self, session: DeviceSession) -> bool:
        try:
            text = session.adb.shell("dumpsys", "power", timeout=5)
        except ADBError:
            return session.screen_awake
        lowered = text.lower()
        if "mwakefulness=asleep" in lowered or "display power: state=off" in lowered:
            return False
        if "mwakefulness=awake" in lowered or "display power: state=on" in lowered:
            return True
        return session.screen_awake

    def _refresh_display(self, session: DeviceSession) -> None:
        try:
            text = session.adb.shell("wm", "size", timeout=5)
        except ADBError:
            return
        matches = _SIZE_RE.findall(text)
        if matches:
            width, height = matches[-1]
            session.display_width, session.display_height = int(width), int(height)

    def _set_screen_state(self, session: DeviceSession, awake: bool) -> None:
        if session.screen_awake == awake:
            return
        session.screen_awake = awake
        self.events.publish(FleetEventType.SCREEN_STATE_CHANGED, session.device_id, screen="AWAKE" if awake else "SLEEPING")

    def _set_state(self, session: DeviceSession, state: DeviceFleetState, safe_error: str | None) -> None:
        old = session.state
        session.state = state
        session.last_safe_error = safe_error
        if old != state:
            self.events.publish(FleetEventType.STATE_CHANGED, session.device_id, previous=old.value, state=state.value)

    def set_pairing(self, session: DeviceSession, pending: Any | None) -> None:
        session.pending_pairing = pending
        self.events.publish(
            FleetEventType.PAIRING_CHANGED,
            session.device_id,
            pairing=pending is not None,
            state=DeviceFleetState.PAIRING.value if pending is not None else session.state.value,
        )
        if pending is not None:
            self._set_state(session, DeviceFleetState.PAIRING, None)

    def remember_credential(self, session: DeviceSession, credential: str | None) -> None:
        session.credential = credential
        remembered = self._remembered.setdefault(session.serial, RememberedSession(session.device_id, local_port=session.local_port))
        remembered.credential = credential
