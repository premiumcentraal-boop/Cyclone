from __future__ import annotations

from ..desktop_runtime.models import deterministic_device_id


class DeviceIdentityError(ValueError):
    """Raised when a requested device_id does not name the connected serial."""


def resolve_device_identity(
    *,
    device_id: str | None = None,
    serial: str | None = None,
) -> dict[str, str | None]:
    """Map device_id and legacy serial onto one phone identity.

    Either identifier is enough. When both are present they must name the same
    device: ``device_id`` may be the canonical ``dev_…`` id **or** the raw serial.
    """
    serial_value = str(serial).strip() if serial not in (None, "") else None
    requested = str(device_id).strip() if device_id not in (None, "") else None
    derived = deterministic_device_id(serial_value) if serial_value else None
    if requested and serial_value:
        aliases = {serial_value, derived}
        if requested not in aliases:
            raise DeviceIdentityError("device_id does not match the connected serial")
    return {
        "device_id": derived or requested,
        "serial": serial_value,
    }
