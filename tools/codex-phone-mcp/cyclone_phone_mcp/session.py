from __future__ import annotations

import re
from typing import Any, Mapping

DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
IDENTITY_KEYS = frozenset({"sessionId", "session_id", "displayId", "display_id", "executionContext"})
SESSION_REQUIRED = "SESSION_REQUIRED"
SESSION_DISPLAY_MISMATCH = "SESSION_DISPLAY_MISMATCH"
PLANE_FOREGROUND = "foreground"
PLANE_SESSION_KERNEL_VD = "session_kernel_vd"
PLANE_LAYER2_WORKSPACE = "layer2_workspace"
PLANE_MISMATCH = "PLANE_MISMATCH"
WORKSPACE_GENERATION_REQUIRED = "WORKSPACE_GENERATION_REQUIRED"
WORKSPACE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
PLANE_LABELS = {
    PLANE_FOREGROUND: "Foreground",
    PLANE_SESSION_KERNEL_VD: "Session Kernel VD",
    PLANE_LAYER2_WORKSPACE: "Layer 2 workspace",
}
PLANES_SUMMARY = {
    "foreground": {
        "kind": PLANE_FOREGROUND,
        "sessionId": DEFAULT_FOREGROUND_SESSION_ID,
        "displayId": 0,
        "label": PLANE_LABELS[PLANE_FOREGROUND],
    },
    "sessionKernelVd": "named session_id + displayId>0; never display 0",
    "layer2Workspace": "workspaceId+workspaceGeneration on default-foreground / display 0; single mutate lock",
}


class SessionScopeError(ValueError):
    """MCP-surface execution identity failure. Never invent default-foreground."""

    def __init__(self, message: str, error_class: str = SESSION_REQUIRED):
        super().__init__(message)
        self.error_class = error_class


def session_scope_error_result(exc: SessionScopeError) -> dict[str, Any]:
    return {
        "ok": False,
        "errorClass": exc.error_class,
        "error": {
            "code": exc.error_class,
            "layer": "protocol",
            "message": str(exc),
        },
    }


def parse_execution_scope(args: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """Return canonical ``{sessionId, displayId}`` or None to omit (legacy default-foreground).

    Execution identity is not usb_session_id, media sessionId, teach sessionId, or MCP report
    sessionId. Omitted identity must not invent a workspace session. A named workspace session
    requires displayId > 0 and must never silently fall back to display 0.
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
        nested_session = _read_session(nested)
        nested_display = _read_display(nested)
        session = _agree("sessionId", session, nested_session)
        display = _agree("displayId", display, nested_display)
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


def parse_tool_execution_scope(args: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """Parse top-level MCP args and nested action params; reject disagreements."""
    if not isinstance(args, Mapping):
        return parse_execution_scope(args)
    top = parse_execution_scope(args)
    params = args.get("params")
    nested = parse_execution_scope(params) if isinstance(params, dict) else None
    return _agree_scope(top, nested)


def require_tool_execution_scope(args: Mapping[str, Any] | None) -> dict[str, Any]:
    """Fail closed unless an explicit sessionId is present. Never invent default-foreground."""
    try:
        scope = parse_tool_execution_scope(args)
    except ValueError as exc:
        raise _scope_error(exc) from exc
    if not scope or not str(scope.get("sessionId") or "").strip():
        raise SessionScopeError(
            "session_id is required. Pass default-foreground for the live human display, "
            "or a named workspace session with display_id > 0.",
            SESSION_REQUIRED,
        )
    return scope


def classify_session_plane(args: Mapping[str, Any] | None) -> dict[str, Any]:
    """Require session via require_tool_execution_scope, then overlay workspace keys.

    Mix rules:
    - workspaceId present XOR workspaceGeneration present → WORKSPACE_GENERATION_REQUIRED
    - workspaceId + named session (not default-foreground) or displayId != 0 → PLANE_MISMATCH
    - workspaceId+generation + default-foreground/display 0 → kind layer2_workspace
    - named session displayId>0, no workspace keys → session_kernel_vd
    - default-foreground display 0, no workspace keys → foreground
    workspaceId must be str matching [A-Za-z0-9_-]{1,80}; generation must be int >= 0 (not bool).
    Read workspace keys from args and args['params'] if dict; they must agree.
    """
    scope = require_tool_execution_scope(args)
    try:
        workspace_id, generation = _read_workspace_keys(args)
        return plane_from_scope(scope, workspace_id, generation)
    except SessionScopeError:
        raise
    except ValueError as exc:
        raise _scope_error(exc) from exc


def attach_plane(result: Any, plane: Mapping[str, Any] | None) -> Any:
    """If result is dict, copy and set result['plane']=plane (and sessionId/displayId if missing)."""
    if not isinstance(result, dict):
        return result
    out = dict(result)
    if plane is not None:
        out["plane"] = dict(plane)
        if "sessionId" not in out and plane.get("sessionId") is not None:
            out["sessionId"] = plane["sessionId"]
        if "displayId" not in out and plane.get("displayId") is not None:
            out["displayId"] = plane["displayId"]
    return out


def plane_from_scope(
    scope: Mapping[str, Any],
    workspace_id: Any = None,
    generation: Any = None,
) -> dict[str, Any]:
    session_id = scope["sessionId"]
    display_id = scope["displayId"]
    if (workspace_id is None) ^ (generation is None):
        raise SessionScopeError(
            "workspaceId and workspaceGeneration must both be present",
            WORKSPACE_GENERATION_REQUIRED,
        )
    if workspace_id is not None:
        _require_workspace_pair(workspace_id, generation)
        if session_id != DEFAULT_FOREGROUND_SESSION_ID or display_id != 0:
            raise SessionScopeError(
                "PLANE_MISMATCH: workspaceId cannot mix with a named session or non-zero displayId",
                PLANE_MISMATCH,
            )
        return _plane_dict(
            PLANE_LAYER2_WORKSPACE, session_id, display_id, workspace_id, generation,
        )
    if session_id == DEFAULT_FOREGROUND_SESSION_ID:
        return _plane_dict(PLANE_FOREGROUND, session_id, display_id)
    return _plane_dict(PLANE_SESSION_KERNEL_VD, session_id, display_id)


def inventory_session_plane(session: Mapping[str, Any]) -> dict[str, Any]:
    """Foreground vs Session Kernel VD from inventory identity. Never label VD as Layer 2."""
    session_id = session.get("sessionId") or session.get("session_id")
    display_id = session.get("displayId")
    if display_id is None:
        display_id = session.get("display_id")
    if session_id == DEFAULT_FOREGROUND_SESSION_ID:
        return _plane_dict(
            PLANE_FOREGROUND,
            DEFAULT_FOREGROUND_SESSION_ID,
            0 if display_id is None else display_id,
        )
    return _plane_dict(PLANE_SESSION_KERNEL_VD, session_id, display_id)


def plane_from_workspace_result(result: Any, fallback: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(result, dict):
        return dict(fallback)
    workspace_id = result.get("workspaceId")
    generation = result.get("workspaceGeneration")
    if isinstance(workspace_id, str) and type(generation) is int and generation >= 0:
        return _plane_dict(
            PLANE_LAYER2_WORKSPACE,
            DEFAULT_FOREGROUND_SESSION_ID,
            0,
            workspace_id,
            generation,
        )
    return dict(fallback)


def _scope_error(exc: ValueError) -> SessionScopeError:
    if isinstance(exc, SessionScopeError):
        return exc
    message = str(exc)
    lowered = message.lower()
    if "generation" in lowered:
        return SessionScopeError(message, WORKSPACE_GENERATION_REQUIRED)
    if "plane" in lowered or ("workspace" in lowered and "display" not in lowered):
        return SessionScopeError(message, PLANE_MISMATCH)
    if "display" in lowered:
        return SessionScopeError(message, SESSION_DISPLAY_MISMATCH)
    if "session" in lowered or "conflicting" in lowered:
        return SessionScopeError(message, SESSION_REQUIRED)
    return SessionScopeError(message, SESSION_REQUIRED)


def attach_execution_scope(params: Mapping[str, Any] | None, scope: Mapping[str, Any] | None) -> dict[str, Any]:
    """Copy canonical sessionId/displayId onto params. Top-level identity is enough for callers."""
    out = dict(params or {})
    existing = parse_execution_scope(out)
    agreed = _agree_scope(dict(scope) if scope else None, existing)
    if agreed is None:
        return out
    out["sessionId"] = agreed["sessionId"]
    out["displayId"] = agreed["displayId"]
    return out


def strip_execution_scope(params: Mapping[str, Any] | None) -> dict[str, Any]:
    return {key: value for key, value in dict(params or {}).items() if key not in IDENTITY_KEYS}


def scope_cache_key(device_id: str | None, session_id: str | None) -> str:
    return f"{device_id or '__gateway_selected__'}::{session_id or DEFAULT_FOREGROUND_SESSION_ID}"


def _agree_scope(left: dict[str, Any] | None, right: dict[str, Any] | None) -> dict[str, Any] | None:
    if left and right:
        if left["sessionId"] != right["sessionId"] or left["displayId"] != right["displayId"]:
            raise ValueError("Conflicting execution session identities")
        return left
    return left or right


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


def _read_workspace_keys(args: Mapping[str, Any] | None) -> tuple[Any, Any]:
    if not isinstance(args, Mapping):
        return None, None
    workspace_id = _raw_workspace_id(args)
    generation = _raw_workspace_generation(args)
    params = args.get("params")
    if isinstance(params, Mapping):
        workspace_id = _agree("workspaceId", workspace_id, _raw_workspace_id(params))
        generation = _agree("workspaceGeneration", generation, _raw_workspace_generation(params))
    return workspace_id, generation


def _raw_workspace_id(source: Mapping[str, Any]) -> Any:
    if "workspaceId" not in source or source["workspaceId"] is None:
        return None
    return source["workspaceId"]


def _raw_workspace_generation(source: Mapping[str, Any]) -> Any:
    if "workspaceGeneration" not in source or source["workspaceGeneration"] is None:
        return None
    return source["workspaceGeneration"]


def _require_workspace_pair(workspace_id: Any, generation: Any) -> None:
    if not isinstance(workspace_id, str) or not WORKSPACE_ID_RE.fullmatch(workspace_id):
        raise SessionScopeError("workspaceId is invalid", WORKSPACE_GENERATION_REQUIRED)
    if type(generation) is not int or generation < 0:
        raise SessionScopeError(
            "workspaceGeneration is required with workspaceId and must be an int >= 0",
            WORKSPACE_GENERATION_REQUIRED,
        )


def _plane_dict(
    kind: str,
    session_id: Any,
    display_id: Any,
    workspace_id: Any = None,
    workspace_generation: Any = None,
) -> dict[str, Any]:
    return {
        "kind": kind,
        "sessionId": session_id,
        "displayId": display_id,
        "workspaceId": workspace_id,
        "workspaceGeneration": workspace_generation,
        "label": PLANE_LABELS[kind],
    }
