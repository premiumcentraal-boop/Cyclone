"""Privacy scrubbing for fleet journals, events, and planner snapshots.

Never persist API keys, passwords, OTPs, or screenshot bytes.
"""

from __future__ import annotations

import re
from typing import Any

_SENSITIVE_KEYS = frozenset({
    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "api_key",
    "apikey", "apikey", "openrouter_key", "openrouterkey", "authorization",
    "cookie", "cvv", "credential", "typed_text", "typed_value", "typedtext",
    "screenshot", "image", "png", "jpeg", "base64", "bytes",
})

_KEY_RE = re.compile(
    r"(?i)\b(?:sk-or-v1-[a-z0-9_\-]{8,}|sk-[a-z0-9]{12,}|bearer\s+[a-z0-9._\-]{12,})"
)
_ASSIGN_RE = re.compile(
    r"(?i)\b(password|passcode|pin|otp|api[_ ]?key|token|secret)\b\s*[:=]\s*\S+"
)
_OTP_RE = re.compile(r"\b\d{4,8}\b")


def scrub_text(value: str) -> str:
    text = _KEY_RE.sub("[redacted]", value)
    return _ASSIGN_RE.sub(lambda match: f"{match.group(1)}=[redacted]", text)


def scrub(value: Any, *, parent_key: str = "") -> Any:
    if isinstance(value, str):
        if parent_key.casefold() in _SENSITIVE_KEYS:
            return "[redacted]"
        return scrub_text(value)
    if isinstance(value, (bytes, bytearray)):
        return "[redacted]"
    if isinstance(value, dict):
        cleaned: dict[str, Any] = {}
        redacted = False
        for key, item in value.items():
            name = str(key)
            if name.casefold() in _SENSITIVE_KEYS:
                redacted = True
                continue
            cleaned[name] = scrub(item, parent_key=name)
        if redacted:
            cleaned["redacted"] = "[redacted]"
        return cleaned
    if isinstance(value, list):
        return [scrub(item, parent_key=parent_key) for item in value[:64]]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return scrub_text(str(value))[:500]


def contains_secret_material(payload: str) -> bool:
    return _KEY_RE.search(payload) is not None or _ASSIGN_RE.search(payload) is not None


def looks_like_secret_handoff(text: str) -> bool:
    folded = text.casefold()
    return any(word in folded for word in ("otp", "one-time", "passcode", "password", "pin code", "verification code", "2fa"))


def redact_objective(text: str) -> str:
    """Keep the task, drop inline secrets and bare codes that follow secret words."""
    cleaned = scrub_text(text)
    if looks_like_secret_handoff(cleaned):
        cleaned = _OTP_RE.sub("[redacted]", cleaned)
    return cleaned[:2000]
