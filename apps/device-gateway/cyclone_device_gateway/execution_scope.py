from __future__ import annotations

import re
from typing import Any, Mapping

DEFAULT_FOREGROUND_SESSION_ID = "default-foreground"
IDENTITY_KEYS = frozenset({"sessionId", "session_id", "displayId", "display_id", "executionContext"})
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
        identity = {"sessionId": session, "displayId": 0}
        _reject_illegal_workspace_mix(args, identity)
        return identity
    if display is None:
        raise ValueError("An explicit background session requires its displayId")
    if display <= 0:
        raise ValueError("Workspace session displayId must be an int > 0")
    identity = {"sessionId": session, "displayId": display}
    _reject_illegal_workspace_mix(args, identity)
    return identity


def attach_execution_identity(payload: Mapping[str, Any] | None, identity: Mapping[str, Any] | None) -> dict[str, Any]:
    out = dict(payload or {})
    if not identity:
        return out
    out["sessionId"] = identity["sessionId"]
    out["displayId"] = identity["displayId"]
    return out


def classify_session_plane(args: Mapping[str, Any] | None) -> dict[str, Any] | None:
    """Overlay workspace keys onto optional execution identity. Omitted identity stays legacy None."""
    identity = parse_execution_identity(args)
    workspace_id, generation = _read_workspace_keys(args)
    if (workspace_id is None) ^ (generation is None):
        raise ValueError(
            "WORKSPACE_GENERATION_REQUIRED: workspaceId and workspaceGeneration must both be present"
        )
    if workspace_id is not None:
        _require_workspace_pair(workspace_id, generation)
        session_id = DEFAULT_FOREGROUND_SESSION_ID if identity is None else identity["sessionId"]
        display_id = 0 if identity is None else identity["displayId"]
        if session_id != DEFAULT_FOREGROUND_SESSION_ID or display_id != 0:
            raise ValueError(
                "PLANE_MISMATCH: workspaceId cannot mix with a named session or non-zero displayId"
            )
        return _plane_dict(PLANE_LAYER2_WORKSPACE, session_id, display_id, workspace_id, generation)
    if identity is None:
        return None
    if identity["sessionId"] == DEFAULT_FOREGROUND_SESSION_ID:
        return _plane_dict(PLANE_FOREGROUND, identity["sessionId"], identity["displayId"])
    return _plane_dict(PLANE_SESSION_KERNEL_VD, identity["sessionId"], identity["displayId"])


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
        raise ValueError("WORKSPACE_GENERATION_REQUIRED: workspaceId is invalid")
    if type(generation) is not int or generation < 0:
        raise ValueError("WORKSPACE_GENERATION_REQUIRED: workspaceGeneration must be an int >= 0")


def _reject_illegal_workspace_mix(args: Mapping[str, Any] | None, identity: Mapping[str, Any]) -> None:
    workspace_id, generation = _read_workspace_keys(args)
    if workspace_id is None and generation is None:
        return
    if (workspace_id is None) ^ (generation is None):
        raise ValueError(
            "WORKSPACE_GENERATION_REQUIRED: workspaceId and workspaceGeneration must both be present"
        )
    _require_workspace_pair(workspace_id, generation)
    if identity["sessionId"] != DEFAULT_FOREGROUND_SESSION_ID or identity["displayId"] != 0:
        raise ValueError(
            "PLANE_MISMATCH: workspaceId cannot mix with a named session or non-zero displayId"
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
