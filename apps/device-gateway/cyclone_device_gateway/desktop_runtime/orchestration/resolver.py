"""Deterministic device-name resolution. Never guesses when two devices match."""

from __future__ import annotations

from dataclasses import dataclass
import re

from .registry import DeviceRecord

_LEADING = re.compile(r"^(?:the|my)\s+", re.IGNORECASE)


@dataclass(frozen=True)
class Resolution:
    status: str  # UNIQUE, AMBIGUOUS, NOT_FOUND
    device: DeviceRecord | None
    matches: tuple[DeviceRecord, ...]
    reason: str

    @property
    def unique(self) -> bool:
        return self.status == "UNIQUE" and self.device is not None


class DeviceNameResolver:
    def resolve(self, query: str, devices: list[DeviceRecord]) -> Resolution:
        raw = " ".join(str(query or "").strip().split())
        if not raw:
            return Resolution("NOT_FOUND", None, (), "empty")
        folded = raw.casefold()
        cleaned = _LEADING.sub("", folded).strip()

        by_id = [device for device in devices if device.device_id == raw or device.device_id.casefold() == folded]
        if len(by_id) == 1:
            return Resolution("UNIQUE", by_id[0], tuple(by_id), "stable-id")

        exact_name = [device for device in devices if device.display_name.casefold() in {folded, cleaned}]
        if len(exact_name) == 1:
            return Resolution("UNIQUE", exact_name[0], tuple(exact_name), "name")
        if len(exact_name) > 1:
            return Resolution("AMBIGUOUS", None, tuple(exact_name), "name")

        alias = [
            device for device in devices
            if any(alias.casefold() in {folded, cleaned} for alias in device.aliases)
        ]
        if len(alias) == 1:
            return Resolution("UNIQUE", alias[0], tuple(alias), "alias")
        if len(alias) > 1:
            return Resolution("AMBIGUOUS", None, tuple(alias), "alias")

        role_query = cleaned.replace(" ", "_").upper()
        role_hits = [device for device in devices if device.role.casefold() == cleaned or device.role == role_query]
        if cleaned in {"work phone", "work"}:
            role_hits = [device for device in devices if device.role == "WORK" or "work" in device.display_name.casefold()]
        elif cleaned in {"personal phone", "personal", "my phone", "phone"}:
            role_hits = [device for device in devices if device.role == "PERSONAL"]
        elif "tablet" in cleaned:
            role_hits = [
                device for device in devices
                if device.role == "MEDIA"
                or "tablet" in device.display_name.casefold()
                or (device.model and "tablet" in device.model.casefold())
            ]
        if len(role_hits) == 1 and (
            cleaned in {"work phone", "work", "personal phone", "personal", "my phone", "phone"}
            or "tablet" in cleaned
            or role_hits[0].role.casefold() == cleaned
        ):
            return Resolution("UNIQUE", role_hits[0], tuple(role_hits), "role")
        if len(role_hits) > 1 and (
            cleaned in {"work phone", "work", "personal phone", "personal", "phone"}
            or "tablet" in cleaned
        ):
            return Resolution("AMBIGUOUS", None, tuple(role_hits), "role")

        model_hits = [
            device for device in devices
            if device.model and (cleaned == device.model.casefold() or cleaned in device.model.casefold())
        ]
        if len(model_hits) == 1:
            return Resolution("UNIQUE", model_hits[0], tuple(model_hits), "model")
        if len(model_hits) > 1:
            return Resolution("AMBIGUOUS", None, tuple(model_hits), "model")

        maker_hits = [
            device for device in devices
            if device.manufacturer and cleaned in device.manufacturer.casefold()
        ]
        if len(maker_hits) == 1:
            return Resolution("UNIQUE", maker_hits[0], tuple(maker_hits), "manufacturer")
        if len(maker_hits) > 1:
            return Resolution("AMBIGUOUS", None, tuple(maker_hits), "manufacturer")

        return Resolution("NOT_FOUND", None, (), "none")
