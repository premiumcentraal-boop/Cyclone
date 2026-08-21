from __future__ import annotations

import json
import re
from typing import Any

_SECRET_KEY = re.compile(r"(?i)(token|secret|password|passcode|api[_-]?key|authorization|credential|otp)")
_SECRET_VALUE = re.compile(r"(?i)^(bearer\s+\S+|sk-[A-Za-z0-9_-]{12,})$")


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
