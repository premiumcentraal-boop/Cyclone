"""Stable wire contracts shared by Cyclone Core and Cyclone Mobile."""

from __future__ import annotations

import hmac
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any
from uuid import uuid4


class ControllerOwner(str, Enum):
    AGENT = "agent"
    HUMAN = "human"


# Read-only tools that may run while Core is waiting for a mandatory fresh
# observation. These are not automatically safe to expose while a human owns
# the phone; screenshot/clipboard/UI reads can contain sensitive information.
NON_MUTATING_TOOLS = frozenset(
    {
        "phone.observe",
        "phone.screenshot",
        "phone.find",
        "phone.get_notifications",
        "phone.get_current_app",
        "phone.get_clipboard",
        "phone.capabilities",
        "phone.wait_for",
        "phone.assert",
    }
)

# HUMAN ownership is deliberately stricter. Core permits only coarse device
# metadata while the user is completing login, 2FA, identity checks, or other
# takeover work. The resume path switches provisionally to AGENT and performs
# a fresh observe there, so no UI/screenshot/clipboard access is needed here.
HUMAN_SAFE_TOOLS = frozenset(
    {
        "phone.get_current_app",
        "phone.capabilities",
    }
)

# Temporary compatibility for the pre-Agent-1 BridgeClient. New mobile code reads
# the typed `tool` + `params`; the legacy bridge can still execute basic actions.
_LEGACY_ACTIONS = {
    "phone.observe": "observe",
    "phone.screenshot": "screenshot",
    "phone.scroll": "scroll",
    "phone.tap": "tap",
    "phone.swipe": "swipe",
    "phone.back": "back",
    "phone.home": "home",
}


@dataclass(frozen=True)
class DeviceDescriptor:
    device_id: str
    name: str
    platform: str = "android"
    capabilities: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class DeviceSessionSnapshot:
    device_id: str
    name: str
    platform: str
    session_id: str
    controller: ControllerOwner
    capabilities: dict[str, str]
    connected_at: datetime
    last_seen_at: datetime
    fresh_observation_required: bool


@dataclass(frozen=True)
class PhoneCommand:
    tool: str
    params: dict[str, Any] = field(default_factory=dict)
    command_id: str = field(default_factory=lambda: f"cmd-{uuid4().hex}")

    def __post_init__(self) -> None:
        if not self.tool.startswith("phone."):
            raise ValueError("Phone commands must use the phone.* namespace.")

    def envelope(self) -> dict[str, Any]:
        envelope: dict[str, Any] = {
            "type": "mobile.command",
            "id": self.command_id,
            "tool": self.tool,
            "params": dict(self.params),
        }
        legacy_action = _LEGACY_ACTIONS.get(self.tool)
        if legacy_action:
            envelope["action"] = legacy_action
            for key, value in self.params.items():
                if key not in {"type", "id", "tool", "action", "params"}:
                    envelope[key] = value
        return envelope


@dataclass(frozen=True)
class PhoneResult:
    command_id: str
    tool: str | None
    ok: bool
    payload: Any = None
    error: dict[str, Any] | None = None
    before_fingerprint: str | None = None
    after_fingerprint: str | None = None
    attempts: int = 1

    @classmethod
    def from_envelope(cls, data: dict[str, Any], *, expected_tool: str | None = None) -> "PhoneResult":
        return cls(
            command_id=str(data.get("id") or data.get("commandId") or ""),
            tool=str(data.get("tool")) if data.get("tool") is not None else expected_tool,
            ok=bool(data.get("ok")),
            payload=data.get("payload"),
            error=data.get("error") if isinstance(data.get("error"), dict) else None,
            before_fingerprint=data.get("beforeFingerprint"),
            after_fingerprint=data.get("afterFingerprint"),
            attempts=max(1, int(data.get("attempts", 1) or 1)),
        )


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def valid_bearer_token(authorization: str | None, expected_token: str) -> bool:
    """Validate the device bootstrap credential without timing-sensitive equality."""

    if not authorization or not authorization.startswith("Bearer "):
        return False
    provided = authorization[len("Bearer ") :]
    if not provided or not expected_token:
        return False
    return hmac.compare_digest(provided.encode("utf-8"), expected_token.encode("utf-8"))
