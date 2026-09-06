"""Shared MCP phone-action contract for Cyclone One v0.2.

Agents historically sent ``phone.tap``, ``packageName``, and Play Store URIs.
This module is the single translation seam: aliases become canonical Android
tools, examples stay exact, and unsupported names list the real allowlist.
"""
from __future__ import annotations

import re
from typing import Any
from urllib.parse import parse_qs, urlparse

ANDROID_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
PLAY_STORE_PACKAGE = "com.android.vending"

CANONICAL_ACTIONS = (
    "phone.click",
    "phone.long_press",
    "phone.swipe",
    "phone.scroll",
    "phone.type",
    "phone.back",
    "phone.home",
    "phone.open_app",
    "phone.launch_intent",
    "phone.wait_for",
)
ACTION_ALIASES = {
    "phone.tap": "phone.click",
    "phone.press": "phone.click",
    "phone.launch": "phone.open_app",
    "phone.open": "phone.open_app",
}
PACKAGE_ALIASES = ("packageName", "appPackage", "pkg", "applicationId")
URI_ALIASES = ("uri", "url", "deeplink", "deepLink", "intentUri", "marketUri")

PLAY_STORE_HOSTS = frozenset({"play.google.com", "market.android.com"})
ALLOWED_LAUNCH_SCHEMES = frozenset({"market", "https", "http"})

PHONE_ACT_EXAMPLES = {
    "phone.click": {
        "tool": "phone.click",
        "params": {"elementId": "<current observation-scoped elementId>"},
        "goal": "Open Play Store",
        "note": "phone.tap is accepted as an alias of phone.click. Locate first; never reuse IDs after a mutation.",
    },
    "phone.open_app": {
        "tool": "phone.open_app",
        "params": {"package": PLAY_STORE_PACKAGE},
        "goal": "Open Google Play Store",
        "note": "The Android field name is package, not packageName. Example package: com.android.vending.",
    },
    "phone.launch_intent": {
        "tool": "phone.launch_intent",
        "params": {"uri": "market://details?id=com.android.chrome"},
        "goal": "Open a Play Store details page",
        "note": "Prefer this over hunting the Play Store home icon. https://play.google.com/store/apps/details?id= is also accepted.",
    },
    "phone.type": {
        "tool": "phone.type",
        "params": {"elementId": "<current observation-scoped elementId>", "text": "Cyclone"},
        "goal": "Type into the Play Store search field",
        "user_authorized": True,
        "note": "Sequence: phone_locate → phone.click to focus → phone.type with the new elementId. user_authorized=true is MCP intent only.",
    },
    "phone.wait_for": {
        "tool": "phone.wait_for",
        "params": {
            "timeoutMs": 8000,
            "condition": {"type": "package_equals", "package": PLAY_STORE_PACKAGE},
        },
        "goal": "Wait until Play Store is foreground",
        "note": "Prefer wait_for on package or on-screen text over blind sleeps.",
    },
}


def supported_actions_message() -> str:
    return (
        "Supported phone_act tools: "
        + ", ".join(CANONICAL_ACTIONS)
        + ". phone.tap is an alias of phone.click."
    )


def format_phone_act_examples() -> str:
    lines = ["Cyclone phone_act examples (one each):"]
    for name in ("phone.click", "phone.open_app", "phone.type"):
        example = PHONE_ACT_EXAMPLES[name]
        lines.append(
            f"  {name}: tool={example['tool']} params={example['params']} goal={example['goal']!r}"
        )
        lines.append(f"    {example['note']}")
    return "\n".join(lines)


def canonical_action_name(tool: str) -> str:
    name = str(tool or "").strip()
    return ACTION_ALIASES.get(name, name)


def resolve_action(tool: str, params: Any) -> tuple[str, dict[str, Any]]:
    """Return canonical tool + params, or raise ValueError with the supported list."""
    if not isinstance(params, dict):
        raise ValueError("params must be an object")
    canonical = canonical_action_name(tool)
    if canonical not in CANONICAL_ACTIONS:
        raise ValueError(f"Unsupported phone action: {tool}. {supported_actions_message()}")
    normalized = dict(params)
    if canonical == "phone.open_app":
        return _resolve_open_app(normalized)
    if canonical == "phone.launch_intent":
        return "phone.launch_intent", _resolve_launch_intent(normalized)
    if canonical == "phone.wait_for":
        return "phone.wait_for", _resolve_wait_for(normalized)
    return canonical, normalized


def play_store_details_uri(package: str) -> str:
    name = str(package or "").strip()
    if not ANDROID_PACKAGE.fullmatch(name):
        raise ValueError("Play Store details URI requires a valid Android package name")
    return f"market://details?id={name}"


def package_from_play_uri(uri: str) -> str | None:
    parsed = urlparse(str(uri or "").strip())
    if parsed.scheme == "market":
        query = parse_qs(parsed.query)
        package = (query.get("id") or [None])[0]
        if package and ANDROID_PACKAGE.fullmatch(package):
            return package
        if parsed.path.startswith("details"):
            package = (query.get("id") or [None])[0]
            if package and ANDROID_PACKAGE.fullmatch(package):
                return package
    host = (parsed.hostname or "").lower()
    if host in PLAY_STORE_HOSTS and "/store/apps/details" in parsed.path:
        package = (parse_qs(parsed.query).get("id") or [None])[0]
        if package and ANDROID_PACKAGE.fullmatch(package):
            return package
    return None


def is_play_store_uri(uri: str) -> bool:
    return package_from_play_uri(uri) is not None or _is_play_store_home(uri)


def _is_play_store_home(uri: str) -> bool:
    parsed = urlparse(str(uri or "").strip())
    if parsed.scheme == "market" and parsed.netloc in {"", "details"}:
        return True
    host = (parsed.hostname or "").lower()
    return host in PLAY_STORE_HOSTS and parsed.path in {"", "/", "/store", "/store/apps"}


def _first_alias(params: dict[str, Any], names: tuple[str, ...]) -> Any:
    for name in names:
        if name in params and params[name] not in (None, ""):
            return params[name]
    return None


def _resolve_open_app(params: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    uri = _first_alias(params, URI_ALIASES)
    package = params.get("package")
    if package in (None, ""):
        package = _first_alias(params, PACKAGE_ALIASES)
    if isinstance(package, str) and package.startswith("market:"):
        uri = package
        package = None
    if isinstance(uri, str) and uri.strip():
        return "phone.launch_intent", _resolve_launch_intent({"uri": uri.strip()})
    name = str(package or "").strip()
    if not ANDROID_PACKAGE.fullmatch(name):
        raise ValueError(
            "phone.open_app requires params.package with a valid Android package name. "
            f"Example: {{\"package\": \"{PLAY_STORE_PACKAGE}\"}}. "
            "packageName is accepted as an alias of package. "
            "For a Play listing use phone.launch_intent with uri=market://details?id=<package>."
        )
    return "phone.open_app", {"package": name}


def _resolve_launch_intent(params: dict[str, Any]) -> dict[str, Any]:
    uri = str(_first_alias(params, URI_ALIASES) or params.get("uri") or "").strip()
    if not uri:
        package = str(params.get("package") or params.get("packageName") or "").strip()
        if ANDROID_PACKAGE.fullmatch(package):
            uri = play_store_details_uri(package)
    if not uri:
        raise ValueError(
            "phone.launch_intent requires params.uri. "
            "Example: {\"uri\": \"market://details?id=com.android.chrome\"}."
        )
    parsed = urlparse(uri)
    scheme = (parsed.scheme or "").lower()
    if scheme not in ALLOWED_LAUNCH_SCHEMES:
        raise ValueError(
            "phone.launch_intent URI scheme is not allowed. "
            "MCP accepts market:// and Play Store https://play.google.com/store/apps/details?id= URIs."
        )
    if scheme in {"http", "https"} and (parsed.hostname or "").lower() not in PLAY_STORE_HOSTS:
        raise ValueError(
            "phone.launch_intent http(s) is limited to Play Store app details URLs."
        )
    if scheme == "market" and package_from_play_uri(uri) is None and not _is_play_store_home(uri):
        raise ValueError("phone.launch_intent market:// URI must be a Play Store details or home link")
    play_package = package_from_play_uri(uri)
    if play_package:
        uri = play_store_details_uri(play_package)
    resolved: dict[str, Any] = {"uri": uri}
    package = str(params.get("package") or "").strip()
    if ANDROID_PACKAGE.fullmatch(package):
        resolved["package"] = package
    elif scheme == "market":
        resolved["package"] = PLAY_STORE_PACKAGE
    return resolved


def _resolve_wait_for(params: dict[str, Any]) -> dict[str, Any]:
    allowed = {
        "condition",
        "timeoutMs",
        "pollMs",
        "selector",
        "elementId",
        "element_id",
        "type",
        "package",
        "packageName",
        "text",
        "from",
    }
    if set(params) - allowed:
        raise ValueError(
            "phone.wait_for accepts timeoutMs, pollMs, and a condition such as "
            "{\"type\": \"package_equals\", \"package\": \"com.android.vending\"} "
            "or {\"type\": \"text_contains\", \"text\": \"Search\"}."
        )
    normalized = dict(params)
    condition = normalized.get("condition")
    if condition is None:
        inferred: dict[str, Any] = {}
        if "type" in normalized:
            inferred["type"] = normalized.pop("type")
        package = normalized.pop("package", None) or normalized.pop("packageName", None)
        if package:
            inferred["package"] = package
        if "text" in normalized and inferred.get("type") in (None, "text_contains"):
            inferred["text"] = normalized.pop("text")
            inferred.setdefault("type", "text_contains")
        if "from" in normalized:
            inferred["from"] = normalized.pop("from")
            inferred.setdefault("type", "fingerprint_changed")
        if inferred:
            if inferred.get("type") == "package_equals" or (
                "package" in inferred and "type" not in inferred
            ):
                inferred["type"] = "package_equals"
            normalized["condition"] = inferred
    elif not isinstance(condition, dict):
        raise ValueError("phone.wait_for condition must be an object")
    else:
        cond = dict(condition)
        if "package" not in cond and "packageName" in cond:
            cond["package"] = cond.pop("packageName")
        normalized["condition"] = cond
    condition = normalized.get("condition")
    if isinstance(condition, dict) and condition.get("type") == "package_equals":
        package = str(condition.get("package") or "").strip()
        if not ANDROID_PACKAGE.fullmatch(package):
            raise ValueError("phone.wait_for package_equals requires a valid Android package name")
        condition["package"] = package
    return normalized
