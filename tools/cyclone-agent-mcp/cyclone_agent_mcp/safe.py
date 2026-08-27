from __future__ import annotations

import json
import re
from typing import Any

_SECRET_KEY = re.compile(r"(?i)(token|secret|password|passcode|api[_-]?key|authorization|credential|otp)")
_SECRET_VALUE = re.compile(r"(?i)^(bearer\s+\S+|sk-[A-Za-z0-9_-]{12,})$")
_FORBIDDEN_OPERATION_KEY = re.compile(
    r"(?i)^(?:cmd|command|shell|adb|powershell|subprocess|executable|script|root|su|docker|host_command)$"
)


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in value.items():
            if _SECRET_KEY.search(str(key)):
                result[str(key)] = "[REDACTED]"
            else:
                result[str(key)] = redact(item)
        return result
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, tuple):
        return tuple(redact(item) for item in value)
    if isinstance(value, str) and _SECRET_VALUE.match(value.strip()):
        return "[REDACTED]"
    return value


def compact_json(value: Any) -> str:
    return json.dumps(redact(value), ensure_ascii=False, separators=(",", ":"))


def validate_typed_params(value: Any, *, path: str = "params") -> None:
    """Reject command-shaped escape hatches while preserving typed phone-action parameters."""
    if isinstance(value, dict):
        for key, item in value.items():
            key_text = str(key)
            if _FORBIDDEN_OPERATION_KEY.fullmatch(key_text):
                raise ValueError(f"{path}.{key_text} is not a permitted typed phone parameter")
            validate_typed_params(item, path=f"{path}.{key_text}")
    elif isinstance(value, list):
        if len(value) > 100:
            raise ValueError(f"{path} exceeds the bounded list size")
        for index, item in enumerate(value):
            validate_typed_params(item, path=f"{path}[{index}]")
