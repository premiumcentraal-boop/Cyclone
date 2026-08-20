from __future__ import annotations

from typing import Any

SENSITIVE_KEYS = {
    "password", "passwd", "passcode", "otp", "token", "api_key", "apikey", "secret",
    "authorization", "cookie", "typed_value", "text_value", "input_value",
}


def _is_sensitive_key(key: str) -> bool:
    normalized = key.lower().replace("-", "_")
    return normalized in SENSITIVE_KEYS or any(part in normalized for part in ("password", "secret", "api_key", "otp"))


def redact(value: Any, parent_key: str = "") -> Any:
    if _is_sensitive_key(parent_key):
        return "<redacted>"
    if isinstance(value, dict):
        return {k: redact(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, parent_key) for v in value]
    return value


def _first(mapping: dict[str, Any], *keys: str, default: Any = None) -> Any:
    for key in keys:
        if key in mapping and mapping[key] not in (None, ""):
            return mapping[key]
    return default


def compact_observation(payload: Any, control_limit: int = 12) -> dict[str, Any]:
    """Normalize flexible Agent-1 payloads into a deliberately small Codex context."""
    if not isinstance(payload, dict):
        return {"raw": redact(payload)}

    if isinstance(payload.get("observation"), dict):
        data = payload["observation"]
    else:
        data = payload.get("result") if isinstance(payload.get("result"), dict) else payload
    device = data.get("device") if isinstance(data.get("device"), dict) else {}
    page = data.get("page") if isinstance(data.get("page"), dict) else {}
    counts = data.get("counts") if isinstance(data.get("counts"), dict) else {}

    controls = page.get("controls") or data.get("controls") or data.get("semanticControls") or []
    if not isinstance(controls, list):
        controls = []

    compact_controls = []
    for item in controls[:control_limit]:
        if not isinstance(item, dict):
            continue
        compact_controls.append(redact({
            "id": _first(item, "id", "elementId", "element_id"),
            "label": _first(item, "label", "semanticName", "text", "contentDescription"),
            "role": _first(item, "role", "className", "class"),
            "resourceId": _first(item, "resourceId", "resource_id"),
            "bounds": item.get("bounds"),
            "actions": _first(item, "androidActions", "actions", default=[]),
            "clickable": item.get("clickable"),
            "scrollable": item.get("scrollable"),
        }))

    routes = data.get("knownRoutes") or data.get("known_routes") or data.get("routeHints") or []
    if not isinstance(routes, list):
        routes = [routes] if routes else []
    brain = data.get("brainRecall") or data.get("brain_recall") or data.get("brainSummary") or ""
    screenshot = data.get("screenshot") or data.get("screenshotRef") or data.get("screenshot_ref")

    return redact({
        "correlationId": _first(payload, "correlation_id", "correlationId"),
        "witness": payload.get("witness") or {},
        "device": {
            "serial": _first(device, "serial", default=data.get("serial")),
            "model": _first(device, "model", default=data.get("model")),
            "android": _first(device, "android", "androidVersion", default=data.get("androidVersion")),
        },
        "package": _first(page, "package", "packageName", default=_first(data, "package", "packageName")),
        "activity": _first(page, "activity", default=data.get("activity")),
        "pageKey": _first(page, "pageKey", "page_key", default=_first(data, "pageKey", "page_key")),
        "title": _first(page, "title", default=data.get("title")),
        "screen": _first(data, "screen", "display", default={}),
        "controls": compact_controls,
        "knownRouteHints": redact(routes[:5]),
        "brainRecall": redact(brain),
        "counts": {
            "raw": _first(counts, "raw", "rawNodeCount", default=_first(data, "rawNodeCount", "raw_count")),
            "semantic": _first(counts, "semantic", "semanticControlCount", default=_first(data, "semanticControlCount", "semantic_count", default=len(controls))),
            "agent": _first(counts, "agent", "agentControlCount", default=_first(data, "agentControlCount", "agent_count")),
        },
        "screenshot": redact(screenshot),
        "previous": redact(data.get("previous") or data.get("previousPageAction") or {}),
        "provenance": redact(data.get("provenance") or {}),
    })
