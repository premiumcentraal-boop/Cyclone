"""Canonical phone-action aliases for the PC Device Gateway.

MCP and Companion agents send ``phone.tap`` / ``packageName`` / Play Store URIs.
Android PhoneToolExecutor already owns ``phone.click``, ``phone.open_app`` and
``phone.launch_intent``; this module only translates names and does not add a
second control engine.
"""
from __future__ import annotations

import re
from typing import Any
from urllib.parse import parse_qs, urlparse

ANDROID_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
PLAY_STORE_PACKAGE = "com.android.vending"
ACTION_ALIASES = {"phone.tap": "phone.click", "phone.press": "phone.click"}
PACKAGE_ALIASES = ("packageName", "appPackage", "pkg", "applicationId")
URI_ALIASES = ("uri", "url", "deeplink", "deepLink", "intentUri", "marketUri")
PLAY_STORE_HOSTS = frozenset({"play.google.com", "market.android.com"})


def canonical_tool(tool: str) -> str:
    return ACTION_ALIASES.get(str(tool or "").strip(), str(tool or "").strip())


def normalize_action(tool: str, params: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    name = canonical_tool(tool)
    payload = dict(params)
    if name == "phone.open_app":
        return _normalize_open_app(payload)
    if name == "phone.launch_intent":
        return "phone.launch_intent", _normalize_launch_intent(payload)
    if name == "phone.wait_for":
        return "phone.wait_for", _normalize_wait_for(payload)
    return name, payload


def _first(params: dict[str, Any], names: tuple[str, ...]) -> Any:
    for key in names:
        if key in params and params[key] not in (None, ""):
            return params[key]
    return None


def _normalize_open_app(params: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    uri = _first(params, URI_ALIASES)
    package = params.get("package") or _first(params, PACKAGE_ALIASES)
    if isinstance(package, str) and package.startswith("market:"):
        uri = package
        package = None
    if isinstance(uri, str) and uri.strip():
        return "phone.launch_intent", _normalize_launch_intent({"uri": uri.strip()})
    name = str(package or "").strip()
    if not ANDROID_PACKAGE.fullmatch(name):
        raise ValueError(
            'phone.open_app requires params.package with a valid Android package name. '
            f'Example: {{"package": "{PLAY_STORE_PACKAGE}"}}'
        )
    return "phone.open_app", {"package": name}


def _normalize_launch_intent(params: dict[str, Any]) -> dict[str, Any]:
    uri = str(_first(params, URI_ALIASES) or "").strip()
    if not uri:
        package = str(params.get("package") or params.get("packageName") or "").strip()
        if ANDROID_PACKAGE.fullmatch(package):
            uri = f"market://details?id={package}"
    parsed = urlparse(uri)
    scheme = (parsed.scheme or "").lower()
    host = (parsed.hostname or "").lower()
    if scheme == "market":
        return {"uri": uri, "package": PLAY_STORE_PACKAGE}
    if scheme in {"http", "https"} and host in PLAY_STORE_HOSTS:
        package = (parse_qs(parsed.query).get("id") or [None])[0]
        resolved = {"uri": uri, "package": PLAY_STORE_PACKAGE}
        if package and ANDROID_PACKAGE.fullmatch(package):
            resolved["uri"] = f"market://details?id={package}"
        return resolved
    raise ValueError(
        "phone.launch_intent accepts market://details?id=<package> or Play Store https details URLs"
    )


def _normalize_wait_for(params: dict[str, Any]) -> dict[str, Any]:
    normalized = dict(params)
    condition = normalized.get("condition")
    if isinstance(condition, dict) and "package" not in condition and "packageName" in condition:
        condition = dict(condition)
        condition["package"] = condition.pop("packageName")
        normalized["condition"] = condition
    elif condition is None:
        inferred: dict[str, Any] = {}
        if "type" in normalized:
            inferred["type"] = normalized.pop("type")
        package = normalized.pop("package", None) or normalized.pop("packageName", None)
        if package:
            inferred["package"] = package
            inferred.setdefault("type", "package_equals")
        if "text" in normalized:
            inferred["text"] = normalized.pop("text")
            inferred.setdefault("type", "text_contains")
        if inferred:
            normalized["condition"] = inferred
    return normalized
