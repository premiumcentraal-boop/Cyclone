"""V4 self-verifying act envelope.

HTTP/transport success is not action success. Every mutating phone action returns
before/after page identity, a control delta, the observation generation the
elementId belonged to, and a redacted after page card.
"""
from __future__ import annotations

from typing import Any


ENVELOPE_KEYS = (
    "ok",
    "pageChanged",
    "before",
    "after",
    "delta",
    "errorClass",
    "generation",
)

SECRET_KEY_PARTS = (
    "password",
    "passwd",
    "passcode",
    "otp",
    "secret",
    "token",
    "api_key",
    "apikey",
)
PASSWORD_HINTS = ("password", "passwd", "passcode", "otp", "pin")
ELEMENT_ID_PREFIXES = ("semantic", "raw", "agent", "uia")


class FailClosedError(Exception):
    """Application-level reject: do not mutate the phone."""

    def __init__(self, error_class: str, generation: str | None = None, message: str = ""):
        super().__init__(message or error_class)
        self.error_class = str(error_class).upper()
        self.generation = generation


def empty_delta() -> dict[str, list[str]]:
    return {"appeared": [], "disappeared": [], "focused": []}


def empty_page_ref() -> dict[str, Any]:
    return {"pageKey": None, "package": None, "activity": None}


def generation_from_element_id(element_id: Any) -> str | None:
    text = str(element_id or "").strip()
    if not text:
        return None
    parts = text.split(":")
    if len(parts) >= 3 and parts[0] in ELEMENT_ID_PREFIXES and parts[1]:
        return parts[1]
    return None


def observation_generation(observation: dict[str, Any] | None) -> str | None:
    if not isinstance(observation, dict):
        return None
    semantic = observation.get("semantic")
    if isinstance(semantic, dict):
        value = semantic.get("observationId") or semantic.get("observation_id")
        if value not in (None, ""):
            return str(value)
    value = observation.get("observationId") or observation.get("observation_id") or observation.get("id")
    return str(value) if value not in (None, "") else None


def _semantic(observation: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(observation, dict):
        return {}
    semantic = observation.get("semantic")
    if isinstance(semantic, dict):
        return semantic
    return observation


def _controls(observation: dict[str, Any] | None) -> list[dict[str, Any]]:
    semantic = _semantic(observation)
    for key in ("semanticControls", "controls", "semantic_controls", "elements"):
        value = semantic.get(key)
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
    if isinstance(observation, dict):
        value = observation.get("controls")
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
    return []


def _control_key(control: dict[str, Any]) -> str:
    for key in ("elementId", "element_id", "id", "resourceId", "resource_id", "label", "text"):
        value = control.get(key)
        if value not in (None, ""):
            return str(value)
    return ""


def _is_secret_key(key: str) -> bool:
    normalized = str(key).lower().replace("-", "_")
    return any(part in normalized for part in SECRET_KEY_PARTS)


def _is_secret_control(control: dict[str, Any]) -> bool:
    if control.get("password") is True or control.get("isPassword") is True:
        return True
    haystack = " ".join(
        str(control.get(key) or "")
        for key in ("role", "class", "inputType", "input_type", "hint", "label", "resourceId", "resource_id")
    ).lower()
    return any(hint in haystack for hint in PASSWORD_HINTS)


def _redact_value(value: Any, parent_key: str = "") -> Any:
    if _is_secret_key(parent_key):
        return "<redacted>"
    if isinstance(value, dict):
        return {str(key): _redact_value(nested, str(key)) for key, nested in value.items()}
    if isinstance(value, list):
        return [_redact_value(item, parent_key) for item in value]
    return value


def redact_control(control: dict[str, Any]) -> dict[str, Any]:
    redacted = _redact_value(dict(control))
    if not isinstance(redacted, dict):
        return {}
    if _is_secret_control(control):
        for key in ("text", "label", "value", "hint", "contentDescription", "content_desc"):
            if key in redacted and redacted[key] not in (None, "", "<redacted>"):
                redacted[key] = "<redacted>"
    return redacted


def _page_text(observation: dict[str, Any] | None) -> str:
    semantic = _semantic(observation)
    explicit = semantic.get("pageText") or semantic.get("page_text")
    secret_values = []
    for control in _controls(observation):
        if _is_secret_control(control):
            for key in ("text", "label", "value", "hint"):
                value = control.get(key)
                if isinstance(value, str) and value.strip() and value != "<redacted>":
                    secret_values.append(value.strip())
    if isinstance(explicit, str) and explicit.strip():
        text = explicit
        for secret in secret_values:
            text = text.replace(secret, "")
        return " ".join(text.split())
    parts: list[str] = []
    for control in _controls(observation):
        if _is_secret_control(control):
            continue
        for key in ("text", "label", "contentDescription", "content_desc"):
            value = control.get(key)
            if isinstance(value, str) and value.strip() and value != "<redacted>":
                parts.append(value.strip())
                break
    return " ".join(parts)


def _page_summary(observation: dict[str, Any] | None) -> str:
    semantic = _semantic(observation)
    explicit = semantic.get("pageSummary") or semantic.get("page_summary")
    if isinstance(explicit, str) and explicit.strip():
        return explicit
    title = semantic.get("pageTitle") or semantic.get("title")
    page_key = semantic.get("pageKey") or (observation or {}).get("page_key")
    package = semantic.get("package") or (observation or {}).get("package")
    bits = [str(item) for item in (title, page_key, package) if item]
    return " · ".join(bits)


def build_page_card(observation: dict[str, Any] | None) -> dict[str, Any]:
    semantic = _semantic(observation)
    raw = observation if isinstance(observation, dict) else {}
    controls = [redact_control(control) for control in _controls(observation)]
    return _redact_value(
        {
            "package": semantic.get("package") or raw.get("package"),
            "activity": semantic.get("activity") or raw.get("activity"),
            "pageKey": semantic.get("pageKey") or raw.get("page_key") or raw.get("pageKey"),
            "title": semantic.get("pageTitle") or semantic.get("title") or raw.get("title"),
            "pageText": _page_text(observation),
            "pageSummary": _page_summary(observation),
            "controls": controls,
        }
    )


def page_ref(observation: dict[str, Any] | None, *, include_card: bool = False) -> dict[str, Any]:
    semantic = _semantic(observation)
    raw = observation if isinstance(observation, dict) else {}
    ref = {
        "pageKey": semantic.get("pageKey") or raw.get("page_key") or raw.get("pageKey"),
        "package": semantic.get("package") or raw.get("package"),
        "activity": semantic.get("activity") or raw.get("activity"),
    }
    if include_card:
        ref["pageCard"] = build_page_card(observation)
    return ref


def compute_delta(
    before: dict[str, Any] | None,
    after: dict[str, Any] | None,
) -> dict[str, list[str]]:
    before_controls = _controls(before)
    after_controls = _controls(after)
    before_ids = {key for control in before_controls if (key := _control_key(control))}
    after_ids = {key for control in after_controls if (key := _control_key(control))}
    appeared = [_control_key(control) for control in after_controls if _control_key(control) and _control_key(control) not in before_ids]
    disappeared = [_control_key(control) for control in before_controls if _control_key(control) and _control_key(control) not in after_ids]
    focused = [_control_key(control) for control in after_controls if control.get("focused") and _control_key(control)]
    return {
        "appeared": appeared,
        "disappeared": disappeared,
        "focused": focused,
    }


def has_coordinate_tap(params: dict[str, Any] | None) -> bool:
    if not isinstance(params, dict):
        return False
    selector = params.get("selector") if isinstance(params.get("selector"), dict) else {}
    return any(key in params for key in ("x", "y")) or any(key in selector for key in ("x", "y"))


def vision_fallback_enabled(params: dict[str, Any] | None, *, explicit: bool = False) -> bool:
    if explicit:
        return True
    if not isinstance(params, dict):
        return False
    return bool(params.get("visionFallback") or params.get("vision_fallback"))


def pop_envelope_flags(params: dict[str, Any]) -> tuple[bool, str | None]:
    """Remove envelope-only flags so Android never sees them."""
    vision = bool(params.pop("visionFallback", False) or params.pop("vision_fallback", False))
    generation = params.pop("generation", None)
    selector = params.get("selector")
    if isinstance(selector, dict):
        vision = bool(vision or selector.pop("visionFallback", False) or selector.pop("vision_fallback", False))
        if generation in (None, ""):
            generation = selector.pop("generation", None)
        else:
            selector.pop("generation", None)
    generation_text = str(generation).strip() if generation not in (None, "") else None
    return vision, generation_text


def build_act_envelope(
    *,
    ok: bool,
    before: dict[str, Any] | None,
    after: dict[str, Any] | None,
    generation: str | None,
    error_class: str | None,
) -> dict[str, Any]:
    before_ref = page_ref(before)
    after_ref = page_ref(after, include_card=True)
    page_changed = bool(
        before_ref.get("pageKey")
        and after_ref.get("pageKey")
        and before_ref.get("pageKey") != after_ref.get("pageKey")
    )
    error = str(error_class).upper() if error_class not in (None, "") else None
    return {
        "ok": bool(ok),
        "pageChanged": page_changed,
        "before": before_ref,
        "after": after_ref,
        "delta": compute_delta(before, after),
        "errorClass": error,
        "generation": generation,
    }
