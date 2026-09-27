"""Persistent fleet registry: stable ids, names, trust, profiles. No secrets."""

from __future__ import annotations

import json
import os
import re
import threading
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Any

from ..models import now_ms
from .redact import scrub

_NAME = re.compile(r"^[\w][\w .+'’-]{0,40}$", re.UNICODE)
_GROUP = re.compile(r"^[a-z0-9][a-z0-9_-]{0,47}$")

DEFAULT_CAPABILITIES: dict[str, bool] = {
    "observe": True,
    "tap": True,
    "type": True,
    "scroll": True,
    "launchApp": True,
    "backgroundExecution": True,
    "screenshots": True,
    "video": True,
    "clipboard": True,
    "notifications": True,
    "camera": False,
    "microphone": False,
    "shell": False,
}


class TrustState(StrEnum):
    DISCOVERED = "DISCOVERED"
    PAIRING = "PAIRING"
    PAIRED = "PAIRED"
    TRUSTED = "TRUSTED"
    SUSPENDED = "SUSPENDED"
    REVOKED = "REVOKED"
    OFFLINE = "OFFLINE"


class DeviceRole(StrEnum):
    PERSONAL = "PERSONAL"
    WORK = "WORK"
    MEDIA = "MEDIA"
    TESTING = "TESTING"
    HOME = "HOME"
    LAB = "LAB"


@dataclass
class DeviceRecord:
    device_id: str
    display_name: str
    aliases: list[str] = field(default_factory=list)
    trust: TrustState = TrustState.DISCOVERED
    role: str = DeviceRole.PERSONAL.value
    manufacturer: str | None = None
    model: str | None = None
    serial_suffix: str | None = None
    capabilities: dict[str, bool] = field(default_factory=lambda: dict(DEFAULT_CAPABILITIES))
    preferred_transport: str = "USB"
    online: bool = False
    last_seen_ms: int = 0
    battery: int | None = None
    current_app: str | None = None
    screen_state: str = "UNKNOWN"
    secure_lock: bool = True
    insecure_lock_dismiss: bool = False
    preferred: bool = False
    restricted_apps: list[str] = field(default_factory=list)
    group_ids: list[str] = field(default_factory=list)
    boot_id: str | None = None

    def public(self) -> dict[str, Any]:
        return {
            "deviceId": self.device_id,
            "name": self.display_name,
            "aliases": list(self.aliases),
            "trust": self.trust.value,
            "trusted": self.trust == TrustState.TRUSTED,
            "role": self.role,
            "manufacturer": self.manufacturer,
            "model": self.model,
            "serialSuffix": self.serial_suffix,
            "capabilities": {key: bool(value) for key, value in self.capabilities.items() if key != "shell" or value is False},
            "preferredTransport": self.preferred_transport,
            "online": self.online,
            "lastSeenMs": self.last_seen_ms,
            "battery": self.battery,
            "currentApp": self.current_app,
            "screenState": self.screen_state,
            "secureLock": self.secure_lock,
            "preferred": self.preferred,
            "restrictedApps": list(self.restricted_apps),
            "groupIds": list(self.group_ids),
        }


class DeviceRegistry:
    SCHEMA_VERSION = 1

    def __init__(self, path: Path | None = None):
        self.path = path
        self._lock = threading.RLock()
        self._devices: dict[str, DeviceRecord] = {}
        self._groups: dict[str, dict[str, Any]] = {}
        self._controller_id: str | None = None
        if path is not None:
            self._load()

    def public(self) -> dict[str, Any]:
        with self._lock:
            return scrub({
                "schemaVersion": self.SCHEMA_VERSION,
                "controllerDeviceId": self._controller_id,
                "devices": [self._devices[key].public() for key in sorted(self._devices)],
                "groups": [dict(self._groups[key]) for key in sorted(self._groups)],
            })

    def get(self, device_id: str) -> DeviceRecord:
        with self._lock:
            record = self._devices.get(device_id)
        if record is None:
            raise KeyError(device_id)
        return record

    def list(self) -> list[DeviceRecord]:
        with self._lock:
            return [self._devices[key] for key in sorted(self._devices)]

    def upsert_discovered(
        self,
        device_id: str,
        *,
        model: str | None = None,
        manufacturer: str | None = None,
        serial_suffix: str | None = None,
        online: bool = True,
        capabilities: dict[str, bool] | None = None,
        battery: int | None = None,
        current_app: str | None = None,
        boot_id: str | None = None,
    ) -> DeviceRecord:
        with self._lock:
            record = self._devices.get(device_id)
            if record is None:
                record = DeviceRecord(
                    device_id=device_id,
                    display_name=self._unique_default_name(manufacturer, model, serial_suffix, device_id),
                    trust=TrustState.DISCOVERED,
                    manufacturer=manufacturer,
                    model=model,
                    serial_suffix=serial_suffix,
                )
                self._devices[device_id] = record
            record.model = model or record.model
            record.manufacturer = manufacturer or record.manufacturer
            record.serial_suffix = serial_suffix or record.serial_suffix
            record.online = online
            record.last_seen_ms = now_ms()
            if battery is not None:
                record.battery = battery
            if current_app is not None:
                record.current_app = current_app
            if capabilities:
                merged = dict(DEFAULT_CAPABILITIES)
                merged.update({key: bool(value) for key, value in capabilities.items() if key in DEFAULT_CAPABILITIES})
                merged["shell"] = False
                record.capabilities = merged
            if boot_id is not None and record.boot_id not in (None, boot_id):
                record.boot_id = boot_id
                record.screen_state = "UNKNOWN"
            elif boot_id is not None:
                record.boot_id = boot_id
            if not online and record.trust == TrustState.TRUSTED:
                record.trust = TrustState.OFFLINE
            self._persist()
            return record

    def mark_offline(self, device_id: str) -> None:
        with self._lock:
            record = self._devices.get(device_id)
            if record is None:
                return
            record.online = False
            if record.trust == TrustState.TRUSTED:
                record.trust = TrustState.OFFLINE
            self._persist()

    def mark_online(self, device_id: str) -> None:
        with self._lock:
            record = self._devices.get(device_id)
            if record is None:
                return
            record.online = True
            record.last_seen_ms = now_ms()
            if record.trust == TrustState.OFFLINE:
                record.trust = TrustState.TRUSTED
            self._persist()

    def pair(self, device_id: str) -> DeviceRecord:
        return self._transition(device_id, TrustState.PAIRED, allowed={
            TrustState.DISCOVERED, TrustState.PAIRING, TrustState.PAIRED, TrustState.OFFLINE,
        })

    def trust(self, device_id: str) -> DeviceRecord:
        record = self._transition(device_id, TrustState.TRUSTED, allowed={
            TrustState.DISCOVERED, TrustState.PAIRING, TrustState.PAIRED, TrustState.SUSPENDED, TrustState.OFFLINE, TrustState.TRUSTED,
        })
        return record

    def suspend(self, device_id: str) -> DeviceRecord:
        return self._transition(device_id, TrustState.SUSPENDED, allowed={
            TrustState.TRUSTED, TrustState.PAIRED, TrustState.OFFLINE, TrustState.SUSPENDED,
        })

    def revoke(self, device_id: str) -> DeviceRecord:
        with self._lock:
            if self._controller_id == device_id:
                self._controller_id = None
        return self._transition(device_id, TrustState.REVOKED, allowed=set(TrustState))

    def forget(self, device_id: str) -> None:
        with self._lock:
            self._devices.pop(device_id, None)
            if self._controller_id == device_id:
                self._controller_id = None
            for group in self._groups.values():
                group["deviceIds"] = [item for item in group["deviceIds"] if item != device_id]
            self._persist()

    def rename(self, device_id: str, name: str, aliases: list[str] | None = None) -> DeviceRecord:
        clean = _clean_name(name)
        with self._lock:
            record = self._require(device_id)
            for other in self._devices.values():
                if other.device_id != device_id and other.display_name.casefold() == clean.casefold():
                    raise ValueError(f"Another device is already named {other.display_name}.")
            record.display_name = clean
            if aliases is not None:
                record.aliases = self._clean_aliases(aliases, device_id)
            self._persist()
            return record

    def set_profile(
        self,
        device_id: str,
        *,
        role: str | None = None,
        preferred: bool | None = None,
        restricted_apps: list[str] | None = None,
        secure_lock: bool | None = None,
        insecure_lock_dismiss: bool | None = None,
        capabilities: dict[str, bool] | None = None,
        group_ids: list[str] | None = None,
    ) -> DeviceRecord:
        with self._lock:
            record = self._require(device_id)
            if role is not None:
                folded = role.strip().upper()
                if folded not in {item.value for item in DeviceRole}:
                    raise ValueError("Unknown device role.")
                record.role = folded
            if preferred is not None:
                record.preferred = bool(preferred)
            if restricted_apps is not None:
                record.restricted_apps = [item.strip() for item in restricted_apps if item.strip()][:32]
            if secure_lock is not None:
                record.secure_lock = bool(secure_lock)
            if insecure_lock_dismiss is not None:
                record.insecure_lock_dismiss = bool(insecure_lock_dismiss)
            if capabilities is not None:
                for key, value in capabilities.items():
                    if key in DEFAULT_CAPABILITIES and key != "shell":
                        record.capabilities[key] = bool(value)
                record.capabilities["shell"] = False
            if group_ids is not None:
                record.group_ids = [item for item in group_ids if _GROUP.fullmatch(item)]
            self._persist()
            return record

    def put_group(self, group_id: str, name: str, device_ids: list[str]) -> dict[str, Any]:
        if not _GROUP.fullmatch(group_id):
            raise ValueError("groupId must contain only lowercase letters, numbers, dash, or underscore")
        clean = _clean_name(name)
        with self._lock:
            unique = []
            for device_id in device_ids:
                if device_id not in self._devices:
                    raise KeyError(device_id)
                if device_id not in unique:
                    unique.append(device_id)
            group = {"groupId": group_id, "name": clean, "deviceIds": unique}
            self._groups[group_id] = group
            for record in self._devices.values():
                ids = [item for item in record.group_ids if item != group_id]
                if record.device_id in unique:
                    ids.append(group_id)
                record.group_ids = ids
            self._persist()
            return dict(group)

    def group_members(self, group_query: str) -> list[DeviceRecord]:
        needle = group_query.strip().casefold()
        needle = re.sub(r"\s+devices?$", "", needle).strip()
        with self._lock:
            match = None
            for group in self._groups.values():
                if group["groupId"] == needle or group["name"].casefold() == needle:
                    match = group
                    break
            if match is None:
                return []
            return [self._devices[device_id] for device_id in match["deviceIds"] if device_id in self._devices]

    def designate_controller(self, device_id: str) -> DeviceRecord:
        with self._lock:
            record = self._require(device_id)
            if record.trust != TrustState.TRUSTED:
                raise ValueError("Only a trusted device can be the fleet controller.")
            self._controller_id = device_id
            self._persist()
            return record

    @property
    def controller_id(self) -> str | None:
        with self._lock:
            return self._controller_id

    def _transition(self, device_id: str, target: TrustState, *, allowed: set[TrustState]) -> DeviceRecord:
        with self._lock:
            record = self._require(device_id)
            if record.trust not in allowed and record.trust != target:
                raise ValueError(f"Cannot move {record.trust.value} to {target.value}.")
            record.trust = target
            self._persist()
            return record

    def _require(self, device_id: str) -> DeviceRecord:
        record = self._devices.get(device_id)
        if record is None:
            raise KeyError(device_id)
        return record

    def _unique_default_name(self, manufacturer: str | None, model: str | None, suffix: str | None, device_id: str) -> str:
        base = " ".join(part for part in (manufacturer, model) if part) or f"Android {suffix or device_id[-4:]}"
        base = base.strip()[:40] or "Android"
        taken = {record.display_name.casefold() for record in self._devices.values()}
        if base.casefold() not in taken:
            return base
        for index in range(2, 40):
            candidate = f"{base} {index}"
            if candidate.casefold() not in taken:
                return candidate
        return f"{base} {device_id[-4:]}"

    def _clean_aliases(self, aliases: list[str], device_id: str) -> list[str]:
        cleaned: list[str] = []
        for alias in aliases[:8]:
            name = _clean_name(alias)
            for other in self._devices.values():
                if other.device_id != device_id and (
                    other.display_name.casefold() == name.casefold()
                    or any(item.casefold() == name.casefold() for item in other.aliases)
                ):
                    raise ValueError(f"Alias {name} is already used.")
            if name.casefold() not in {item.casefold() for item in cleaned}:
                cleaned.append(name)
        return cleaned

    def _load(self) -> None:
        if self.path is None or not self.path.is_file():
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        if payload.get("schemaVersion") != self.SCHEMA_VERSION:
            return
        self._controller_id = payload.get("controllerDeviceId")
        for item in payload.get("devices") or []:
            if not isinstance(item, dict):
                continue
            device_id = str(item.get("deviceId") or "")
            if not device_id.startswith("dev_"):
                continue
            capabilities = dict(DEFAULT_CAPABILITIES)
            raw_caps = item.get("capabilities") if isinstance(item.get("capabilities"), dict) else {}
            for key, value in raw_caps.items():
                if key in capabilities and key != "shell":
                    capabilities[key] = bool(value)
            capabilities["shell"] = False
            trust_raw = str(item.get("trust") or TrustState.DISCOVERED.value)
            try:
                trust = TrustState(trust_raw)
            except ValueError:
                trust = TrustState.DISCOVERED
            self._devices[device_id] = DeviceRecord(
                device_id=device_id,
                display_name=str(item.get("name") or device_id)[:40],
                aliases=[str(alias) for alias in (item.get("aliases") or [])][:8],
                trust=trust,
                role=str(item.get("role") or DeviceRole.PERSONAL.value),
                manufacturer=item.get("manufacturer"),
                model=item.get("model"),
                serial_suffix=item.get("serialSuffix"),
                capabilities=capabilities,
                preferred_transport=str(item.get("preferredTransport") or "USB"),
                online=False,
                last_seen_ms=int(item.get("lastSeenMs") or 0),
                secure_lock=bool(item.get("secureLock", True)),
                preferred=bool(item.get("preferred", False)),
                restricted_apps=[str(app) for app in (item.get("restrictedApps") or [])][:32],
                group_ids=[str(group) for group in (item.get("groupIds") or [])][:16],
            )
        for group in payload.get("groups") or []:
            if isinstance(group, dict) and _GROUP.fullmatch(str(group.get("groupId") or "")):
                self._groups[group["groupId"]] = {
                    "groupId": group["groupId"],
                    "name": str(group.get("name") or group["groupId"])[:40],
                    "deviceIds": [device_id for device_id in group.get("deviceIds") or [] if device_id in self._devices],
                }

    def _persist(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(self.public(), indent=2, sort_keys=True), encoding="utf-8")
        os.replace(temporary, self.path)


def _clean_name(name: str) -> str:
    clean = " ".join(str(name or "").strip().split())
    if not clean or len(clean) > 40 or not _NAME.fullmatch(clean):
        raise ValueError("Device name must be 1..40 letters, numbers, spaces, or simple punctuation.")
    return clean
