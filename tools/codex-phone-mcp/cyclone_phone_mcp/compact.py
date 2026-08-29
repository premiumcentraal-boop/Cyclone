from __future__ import annotations

import re
from typing import Any

SENSITIVE_KEYS = {
    "password", "passwd", "passcode", "otp", "token", "api_key", "apikey", "secret",
    "authorization", "cookie", "typed_value", "text_value", "input_value",
}

PAGE_TEXT_SCHEMA = "cyclone-page-text-v1"
PAGE_SUMMARY_SCHEMA = "cyclone-page-summary-v1"
# Compact window is a size budget, not a product identity. Goal-rank / interactive-first
# decide which controls occupy it. Do not treat this number as "exactly N controls."
DEFAULT_COMPACT_LIMIT = 24
MAX_COMPACT_LIMIT = 80
ROUTE_HINT_LIMIT = 5
PAGE_TEXT_LIMIT = 900
PAGE_SUMMARY_LIMIT = 500
_GOAL_STOP_WORDS = frozenset({
    "a", "an", "and", "button", "click", "for", "go", "in", "into", "me", "my",
    "of", "on", "open", "page", "screen", "tap", "the", "this", "to", "with",
})
_SENSITIVE_VALUE_KEYS = ("text", "text_value", "typed_value", "input_value", "value", "contentDescription")


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


def _as_text(value: Any) -> str:
    if isinstance(value, str):
        return re.sub(r"\s+", " ", value).strip()
    if isinstance(value, (int, float, bool)):
        return str(value)
    if isinstance(value, list):
        parts = [_as_text(item) for item in value[:40]]
        return " ".join(part for part in parts if part)
    return ""


def _truncate_with_note(text: str, limit: int, schema: str) -> tuple[str, str | None]:
    text = _as_text(text)
    if len(text) <= limit:
        return text, None
    clipped = text[:limit].rstrip()
    if clipped.endswith("…"):
        body = clipped
    else:
        body = clipped + "…"
    return body, f"truncated to {limit} chars ({schema}); full tree is not included"


def _tokens(value: str) -> list[str]:
    return [
        token for token in re.findall(r"[a-z0-9]{2,}", value.lower())
        if token not in _GOAL_STOP_WORDS
    ][:16]


def _unwrap_observation(payload: Any) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(payload, dict):
        return {}, {}
    if isinstance(payload.get("observation"), dict):
        return payload, payload["observation"]
    result = payload.get("result")
    if isinstance(result, dict):
        return payload, result
    return payload, payload


def _source_controls(data: dict[str, Any], page: dict[str, Any]) -> list[Any]:
    controls = page.get("controls") or data.get("controls") or data.get("semanticControls") or []
    return controls if isinstance(controls, list) else []


def _is_sensitive_control(item: dict[str, Any]) -> bool:
    if item.get("password") is True or item.get("sensitive") is True:
        return True
    haystack = " ".join(
        str(item.get(key) or "")
        for key in ("role", "className", "class", "resourceId", "resource_id", "inputType", "input_type", "label")
    ).lower()
    return any(part in haystack for part in ("password", "passwd", "passcode", "otp", "pinentry"))


def _sensitive_values(controls: list[Any]) -> list[str]:
    values: list[str] = []
    for item in controls:
        if not isinstance(item, dict) or not _is_sensitive_control(item):
            continue
        for key in _SENSITIVE_VALUE_KEYS:
            value = item.get(key)
            if isinstance(value, str) and value.strip() and value.strip().lower() not in {"password", "otp", "passcode"}:
                values.append(value.strip())
    return values


def _strip_secrets(text: str, secrets: list[str]) -> str:
    if not text or not secrets:
        return text
    cleaned = text
    for secret in sorted(secrets, key=len, reverse=True):
        if secret:
            cleaned = cleaned.replace(secret, "")
    return re.sub(r"\s+", " ", cleaned).strip()


def _compact_control(item: Any) -> dict[str, Any] | None:
    if not isinstance(item, dict):
        return None
    element_id = _first(item, "id", "elementId", "element_id")
    control = {
        "id": element_id,
        "elementId": element_id,
        "label": _first(item, "label", "semanticName", "text", "contentDescription"),
        "role": _first(item, "role", "className", "class"),
        "resourceId": _first(item, "resourceId", "resource_id"),
        "bounds": item.get("bounds"),
        "actions": _first(item, "androidActions", "actions", default=[]),
        "clickable": item.get("clickable"),
        "scrollable": item.get("scrollable"),
        "important": item.get("important"),
        "editable": item.get("editable") if isinstance(item.get("editable"), bool) else None,
    }
    if item.get("password") is True:
        control["password"] = True
        control["label"] = _first(item, "label", "semanticName") or "Password"
    return {key: value for key, value in control.items() if value not in (None, "", [], {})}


def _searchable(control: dict[str, Any]) -> str:
    return " ".join(str(control.get(key) or "") for key in ("label", "resourceId", "role", "id", "elementId")).lower()


def _goal_score(control: dict[str, Any], goal: str) -> int:
    phrase = goal.lower().strip()
    if not phrase:
        return 0
    searchable = _searchable(control)
    tokens = _tokens(goal)
    score = 0
    if phrase and phrase in searchable:
        score += 20
    score += 10 * sum(1 for token in tokens if token in searchable)
    if control.get("clickable") is True:
        score += 1
    if control.get("important") is True:
        score += 1
    return score


def _importance(control: dict[str, Any]) -> int:
    score = 0
    if control.get("clickable") is True:
        score += 4
    if control.get("important") is True:
        score += 3
    if control.get("editable") is True:
        score += 2
    if control.get("scrollable") is True:
        score += 1
    if control.get("label"):
        score += 1
    return score


def _rank_controls(controls: list[dict[str, Any]], goal: str | None) -> list[dict[str, Any]]:
    if goal and str(goal).strip():
        scored = [(_goal_score(control, str(goal)), -index, control) for index, control in enumerate(controls)]
        scored.sort(reverse=True, key=lambda entry: (entry[0], entry[1]))
        ranked: list[dict[str, Any]] = []
        for score, _, control in scored:
            item = dict(control)
            if score:
                item["goalScore"] = score
            ranked.append(item)
        return ranked
    scored = [(_importance(control), -index, control) for index, control in enumerate(controls)]
    scored.sort(reverse=True, key=lambda entry: (entry[0], entry[1]))
    return [control for _, _, control in scored]


def _parse_cursor(cursor: str | None) -> int:
    if not cursor:
        return 0
    match = re.fullmatch(r"c:(\d{1,6})", str(cursor).strip())
    if not match:
        return 0
    return int(match.group(1))


def _window_limit(control_limit: int | None) -> int:
    if control_limit is None:
        return DEFAULT_COMPACT_LIMIT
    try:
        limit = int(control_limit)
    except (TypeError, ValueError):
        return DEFAULT_COMPACT_LIMIT
    if limit <= 0:
        return DEFAULT_COMPACT_LIMIT
    return min(limit, MAX_COMPACT_LIMIT)


def _counts(data: dict[str, Any], source_controls: list[Any], compact_count: int) -> dict[str, Any]:
    counts = data.get("counts") if isinstance(data.get("counts"), dict) else {}
    return {
        "raw": _first(counts, "raw", "rawNodeCount", default=_first(data, "rawNodeCount", "raw_count")),
        "semantic": _first(
            counts,
            "semantic",
            "semanticControlCount",
            default=_first(data, "semanticControlCount", "semantic_count", default=len(source_controls)),
        ),
        "agent": _first(counts, "agent", "agentControlCount", default=_first(data, "agentControlCount", "agent_count")),
        "compact": compact_count,
    }


def _route_hints(data: dict[str, Any], page: dict[str, Any]) -> list[Any]:
    routes = (
        page.get("routeHints")
        or data.get("routeHints")
        or data.get("nextHopHints")
        or data.get("knownRoutes")
        or data.get("known_routes")
        or []
    )
    if not isinstance(routes, list):
        routes = [routes] if routes else []
    return routes[:ROUTE_HINT_LIMIT]


def _last_transition(data: dict[str, Any], page: dict[str, Any]) -> Any:
    return _first(
        page,
        "lastTransition",
        "last_transition",
        default=_first(data, "lastTransition", "last_transition", "previous", "previousPageAction"),
    )


def _synthesize_page_text(page: dict[str, Any], data: dict[str, Any], controls: list[dict[str, Any]], secrets: list[str]) -> str:
    provided = _as_text(_first(page, "pageText", "page_text", default=_first(data, "pageText", "page_text")))
    if provided:
        return _strip_secrets(provided, secrets)
    parts: list[str] = []
    title = _as_text(_first(page, "title", default=data.get("title")))
    if title:
        parts.append(title)
    for control in controls:
        if control.get("password") is True:
            continue
        label = _as_text(control.get("label"))
        if label:
            parts.append(label)
    return _strip_secrets(" · ".join(parts), secrets)


def _synthesize_page_summary(page: dict[str, Any], data: dict[str, Any], page_text: str, package: Any, activity: Any, page_key: Any, title: Any) -> str:
    provided = _as_text(_first(page, "pageSummary", "page_summary", default=_first(data, "pageSummary", "page_summary")))
    if provided:
        return provided
    if title:
        where = str(title)
        if package:
            return f"{where} ({package})"
        return where
    if page_key:
        return str(page_key)
    if page_text:
        return page_text.split(" · ", 1)[0][:PAGE_SUMMARY_LIMIT]
    if package or activity:
        return " · ".join(str(part) for part in (package, activity) if part)
    return ""


def _empty_page_card(payload: Any) -> dict[str, Any]:
    return redact({
        "kind": "page_card",
        "package": None,
        "activity": None,
        "pageKey": None,
        "title": None,
        "pageText": "",
        "pageSummary": "",
        "pageTextSchema": PAGE_TEXT_SCHEMA,
        "pageSummarySchema": PAGE_SUMMARY_SCHEMA,
        "controls": [],
        "routeHints": [],
        "knownRouteHints": [],
        "counts": {"raw": None, "semantic": 0, "agent": None, "compact": 0},
        "truncated": False,
        "raw": payload,
    })


def compact_observation(obs: Any, goal: str | None = None, *, cursor: str | None = None, control_limit: int | None = None) -> dict[str, Any]:
    """Return a page card: where the phone is, surviving pageText/pageSummary, goal-ranked controls.

    Never emits a raw accessibility tree. ``pageText`` / ``pageSummary`` keys always survive.
    Compact length is a window with optional ``nextCursor``, not an "exactly N controls" contract.
    """
    if not isinstance(obs, dict):
        return _empty_page_card(obs)

    envelope, data = _unwrap_observation(obs)
    if not isinstance(data, dict):
        return _empty_page_card(obs)

    page = data.get("page") if isinstance(data.get("page"), dict) else {}
    source_controls = _source_controls(data, page)
    secrets = _sensitive_values(source_controls)
    compact_controls = [control for item in source_controls if (control := _compact_control(item))]
    ranked = _rank_controls(compact_controls, goal)
    offset = _parse_cursor(cursor)
    limit = _window_limit(control_limit)
    window = ranked[offset:offset + limit]
    remaining = len(ranked) - (offset + len(window))
    next_cursor = f"c:{offset + len(window)}" if remaining > 0 else None

    package = _first(page, "package", "packageName", default=_first(data, "package", "packageName"))
    activity = _first(page, "activity", default=data.get("activity"))
    page_key = _first(page, "pageKey", "page_key", default=_first(data, "pageKey", "page_key"))
    title = _first(page, "title", default=data.get("title"))

    page_text = _synthesize_page_text(page, data, ranked, secrets)
    page_text, page_text_note = _truncate_with_note(page_text, PAGE_TEXT_LIMIT, PAGE_TEXT_SCHEMA)
    page_summary = _strip_secrets(
        _synthesize_page_summary(page, data, page_text, package, activity, page_key, title),
        secrets,
    )
    page_summary, page_summary_note = _truncate_with_note(page_summary, PAGE_SUMMARY_LIMIT, PAGE_SUMMARY_SCHEMA)

    device = data.get("device") if isinstance(data.get("device"), dict) else {}
    routes = _route_hints(data, page)
    last_transition = _last_transition(data, page)
    brain = data.get("brainRecall") or data.get("brain_recall") or data.get("brainSummary") or ""
    screenshot = (
        data.get("screenshot") or data.get("screenshotRef") or data.get("screenshot_ref")
        or envelope.get("screenshot") or envelope.get("screenshotRef") or envelope.get("screenshot_ref")
    )
    witness = envelope.get("witness") if isinstance(envelope.get("witness"), dict) else {}

    card: dict[str, Any] = {
        "kind": "page_card",
        "correlationId": _first(envelope, "correlation_id", "correlationId"),
        "witness": witness,
        "device": {
            "serial": _first(device, "serial", default=data.get("serial")),
            "model": _first(device, "model", default=data.get("model")),
            "android": _first(device, "android", "androidVersion", default=data.get("androidVersion")),
        },
        "package": package,
        "activity": activity,
        "pageKey": page_key,
        "title": title,
        "pageText": page_text,
        "pageSummary": page_summary,
        "pageTextSchema": PAGE_TEXT_SCHEMA,
        "pageSummarySchema": PAGE_SUMMARY_SCHEMA,
        "screen": _first(data, "screen", "display", default={}),
        "controls": window,
        "routeHints": routes,
        "knownRouteHints": routes,
        "brainRecall": brain,
        "counts": _counts(data, source_controls, len(window)),
        "screenshot": screenshot,
        "previous": data.get("previous") or data.get("previousPageAction") or {},
        "provenance": data.get("provenance") or {},
        "truncated": remaining > 0 or bool(page_text_note or page_summary_note),
    }
    if last_transition not in (None, "", {}, []):
        card["lastTransition"] = last_transition
    if next_cursor:
        card["nextCursor"] = next_cursor
    if page_text_note:
        card["pageTextNote"] = page_text_note
    if page_summary_note:
        card["pageSummaryNote"] = page_summary_note
    if goal and str(goal).strip():
        card["goal"] = str(goal).strip()
    return redact(card)
