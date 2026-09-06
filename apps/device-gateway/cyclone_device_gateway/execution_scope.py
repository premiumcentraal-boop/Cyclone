from __future__ import annotations

from typing import Any, Mapping

DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
IDENTITY_KEYS = frozenset({"sessionId", "session_id", "displayId", "display_id", "executionContext"})


def parse_execution_identity(args: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """Canonical `{sessionId, displayId}` or None to omit (legacy default-foreground).

    Execution identity is not usb_session_id, media sessionId, teach sessionId, or MCP report
    sessionId. Named workspace sessions require displayId > 0; never silently send display 0.
    """
    if args is None:
        return None
    if not isinstance(args, Mapping):
        raise ValueError("execution scope args must be an object")
    session = _read_session(args)
    display = _read_display(args)
    nested = args.get("executionContext") if "executionContext" in args else None
    if nested is not None:
        if not isinstance(nested, Mapping):
            raise ValueError("executionContext must be an object")
        session = _agree("sessionId", session, _read_session(nested))
        display = _agree("displayId", display, _read_display(nested))
    params = args.get("params") if isinstance(args.get("params"), Mapping) else None
    if params is not None:
        session = _agree("sessionId", session, _read_session(params))
        display = _agree("displayId", display, _read_display(params))
        nested_params = params.get("executionContext") if "executionContext" in params else None
        if nested_params is not None:
            if not isinstance(nested_params, Mapping):
                raise ValueError("executionContext must be an object")
            session = _agree("sessionId", session, _read_session(nested_params))
            display = _agree("displayId", display, _read_display(nested_params))
    if session is None and display is None:
        return None
    if session is None:
        if display == 0:
            return None
        raise ValueError("displayId requires sessionId")
    if session == DEFAULT_FOREGROUND_SESSION_ID:
        if display is None:
            display = 0
        if display != 0:
            raise ValueError("default-foreground session must use display 0")
        return {"sessionId": session, "displayId": 0}
    if display is None:
        raise ValueError("An explicit background session requires its displayId")
    if display <= 0:
        raise ValueError("Workspace session displayId must be an int > 0")
    return {"sessionId": session, "displayId": display}


def attach_execution_identity(payload: Mapping[str, Any] | None, identity: Mapping[str, Any] | None) -> dict[str, Any]:
    out = dict(payload or {})
    if not identity:
        return out
    out["sessionId"] = identity["sessionId"]
    out["displayId"] = identity["displayId"]
    return out


def history_key(device_id: str, identity: Mapping[str, Any] | None) -> str:
    session_id = (identity or {}).get("sessionId") or DEFAULT_FOREGROUND_SESSION_ID
    return f"{device_id}::{session_id}"


def _read_session(args: Mapping[str, Any]) -> str | None:
    return _agree("sessionId", _session_value(args, "session_id"), _session_value(args, "sessionId"))


def _read_display(args: Mapping[str, Any]) -> int | None:
    return _agree("displayId", _display_value(args, "display_id"), _display_value(args, "displayId"))


def _session_value(args: Mapping[str, Any], key: str) -> str | None:
    if key not in args or args[key] is None:
        return None
    value = args[key]
    if not isinstance(value, str) or not value or value != value.strip():
        raise ValueError("Invalid sessionId")
    return value


def _display_value(args: Mapping[str, Any], key: str) -> int | None:
    if key not in args or args[key] is None:
        return None
    value = args[key]
    if isinstance(value, bool) or isinstance(value, str) or not isinstance(value, (int, float)):
        raise ValueError("Invalid displayId")
    if float(value) != int(value) or int(value) < 0:
        raise ValueError("Invalid displayId")
    return int(value)


def _agree(name: str, left: Any, right: Any) -> Any:
    if left is not None and right is not None and left != right:
        raise ValueError(f"Conflicting {name} aliases")
    return left if left is not None else right
