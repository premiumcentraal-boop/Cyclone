"""Screen power and lock state. Never bypasses a secure lock."""

from __future__ import annotations

from enum import StrEnum
from typing import Callable

from .registry import DeviceRecord


class ScreenState(StrEnum):
    AWAKE = "AWAKE"
    ASLEEP = "ASLEEP"
    LOCKED = "LOCKED"
    UNLOCKED = "UNLOCKED"
    UNKNOWN = "UNKNOWN"
    OWNER_REQUIRED = "OWNER_REQUIRED"


class DevicePowerController:
    """Single place for wake / lock decisions.

    `wake` and `dismiss_insecure` are injected by the transport. This class
    never emits ADB keyevents and never accepts a PIN.
    """

    def __init__(
        self,
        probe: Callable[[str], ScreenState | str],
        *,
        wake: Callable[[str], None] | None = None,
        dismiss_insecure: Callable[[str], None] | None = None,
        record_lookup: Callable[[str], DeviceRecord | None] | None = None,
    ):
        self._probe = probe
        self._wake = wake
        self._dismiss_insecure = dismiss_insecure
        self._record_lookup = record_lookup
        self.owner_requests: list[dict[str, str]] = []
        self.wake_calls: list[str] = []
        self.dismiss_calls: list[str] = []

    def get_screen_state(self, device_id: str) -> ScreenState:
        raw = self._probe(device_id)
        try:
            return raw if isinstance(raw, ScreenState) else ScreenState(str(raw))
        except ValueError:
            return ScreenState.UNKNOWN

    def ensure_awake(self, device_id: str) -> ScreenState:
        record = self._record_lookup(device_id) if self._record_lookup else None
        state = self.get_screen_state(device_id)
        if state == ScreenState.ASLEEP and self._wake is not None:
            self._wake(device_id)
            self.wake_calls.append(device_id)
            state = self.get_screen_state(device_id)
        if state in {ScreenState.UNLOCKED, ScreenState.AWAKE}:
            return ScreenState.UNLOCKED if state == ScreenState.UNLOCKED else ScreenState.AWAKE
        secure = True if record is None else record.secure_lock
        if state == ScreenState.LOCKED and secure:
            self.request_owner_unlock(device_id)
            return ScreenState.OWNER_REQUIRED
        if state == ScreenState.LOCKED and record is not None and record.insecure_lock_dismiss and self._dismiss_insecure:
            self._dismiss_insecure(device_id)
            self.dismiss_calls.append(device_id)
            state = self.get_screen_state(device_id)
            if state in {ScreenState.UNLOCKED, ScreenState.AWAKE}:
                return ScreenState.UNLOCKED
        if state == ScreenState.LOCKED:
            self.request_owner_unlock(device_id)
            return ScreenState.OWNER_REQUIRED
        if state == ScreenState.UNKNOWN:
            return ScreenState.UNKNOWN
        return state

    def request_owner_unlock(self, device_id: str) -> dict[str, str]:
        record = self._record_lookup(device_id) if self._record_lookup else None
        name = record.display_name if record else device_id
        request = {
            "deviceId": device_id,
            "code": "DEVICE_NEEDS_OWNER",
            "message": f"{name} needs you to unlock the phone before I can continue.",
        }
        self.owner_requests.append(request)
        return request

    def wait_until_unlocked(self, device_id: str, *, attempts: int = 1) -> ScreenState:
        """Non-blocking poll. Does not send credentials. Caller resumes after the owner unlocks."""
        state = self.get_screen_state(device_id)
        if state in {ScreenState.UNLOCKED, ScreenState.AWAKE}:
            return ScreenState.UNLOCKED
        if attempts <= 0:
            return state
        return state
