"""Soft-success for PROTOCOL_MISMATCH when the phone UI actually changed.

Live Pixel sessions opened Play Store (afterPackage=com.android.vending,
pageChanged=true) but agents stopped because the capability envelope was
classified PROTOCOL_MISMATCH. When the observed UI effect matches, ok follows
the UI; the mismatch is a warning, not a hard failure.
"""
from __future__ import annotations

from typing import Any

from .action_contract import PLAY_STORE_PACKAGE, package_from_play_uri
from .protocol import CAPABILITY_PROTOCOL_VERSION, Failure, classify_failure

SOFT_SUCCESS_WARNING = (
    "Protocol envelope was incomplete; the observed UI effect was treated as success."
)


def _text(value: Any) -> str:
    return str(value or "").strip()


def _mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def package_of(*payloads: Any) -> str:
    for payload in payloads:
        data = _mapping(payload)
        if not data:
            continue
        location = _mapping(data.get("location"))
        after_state = _mapping(data.get("afterState"))
        after = _mapping(data.get("after"))
        for source in (data, location, after_state, after, _mapping(data.get("afterPageCard"))):
            for key in ("package", "packageName", "afterPackage", "after_package"):
                text = _text(source.get(key))
                if text:
                    return text
    return ""


def page_key_of(*payloads: Any) -> str:
    for payload in payloads:
        data = _mapping(payload)
        location = _mapping(data.get("location"))
        after_state = _mapping(data.get("afterState"))
        for source in (data, location, after_state, _mapping(data.get("after")), _mapping(data.get("afterPageCard"))):
            text = _text(source.get("pageKey") or source.get("page_key"))
            if text:
                return text
    return ""


def expected_package(tool: str, params: dict[str, Any] | None) -> str:
    params = _mapping(params)
    if tool == "phone.open_app":
        return _text(params.get("package") or params.get("packageName"))
    if tool == "phone.launch_intent":
        uri = _text(params.get("uri") or params.get("url"))
        return package_from_play_uri(uri) or _text(params.get("package")) or PLAY_STORE_PACKAGE
    if tool == "phone.wait_for":
        condition = _mapping(params.get("condition")) or params
        if _text(condition.get("type")) in {"", "package_equals"}:
            return _text(condition.get("package") or condition.get("packageName"))
    return ""


def page_changed_flag(raw: Any) -> bool:
    data = _mapping(raw)
    if data.get("pageChanged") is True:
        return True
    verification = _mapping(data.get("verification"))
    if verification.get("pageChanged") is True:
        return True
    status = _text(verification.get("status")).upper()
    return status in {"PAGE_CHANGED", "PAGECHANGED"}


def ui_effect_evidence(
    tool: str,
    params: dict[str, Any] | None,
    *,
    before: Any = None,
    after: Any = None,
    raw: Any = None,
) -> dict[str, Any]:
    after_package = package_of(after, raw)
    before_package = package_of(before)
    expected = expected_package(tool, params)
    after_page = page_key_of(after, raw)
    before_page = page_key_of(before)
    changed = page_changed_flag(raw) or bool(
        (after_package and before_package and after_package != before_package)
        or (after_page and before_page and after_page != before_page)
    )
    expected_hit = bool(expected and after_package == expected)
    matched = bool(expected_hit or changed)
    return {
        "matched": matched,
        "pageChanged": changed,
        "afterPackage": after_package or None,
        "beforePackage": before_package or None,
        "expectedPackage": expected or None,
        "afterPageKey": after_page or None,
        "beforePageKey": before_page or None,
    }


def apply_action_soft_success(
    tool: str,
    params: dict[str, Any] | None,
    result: Any,
    *,
    before: Any = None,
    after: Any = None,
) -> Any:
    """If PROTOCOL_MISMATCH is the only failure and the UI moved, stamp ok=true."""
    if not isinstance(result, dict):
        return result
    error = result.get("error")
    error_code = _text(_mapping(error).get("code")).upper() if isinstance(error, dict) else ""
    if error_code and error_code != "PROTOCOL_MISMATCH":
        return result
    evidence = ui_effect_evidence(tool, params, before=before, after=after, raw=result)
    failure = classify_failure(result)
    protocol_mismatch = (
        error_code == "PROTOCOL_MISMATCH"
        or (failure is not None and failure.code == "PROTOCOL_MISMATCH")
        or (result.get("ok") is not True and failure is not None and failure.code == "PROTOCOL_MISMATCH")
    )
    if not evidence["matched"]:
        return result
    if not protocol_mismatch and result.get("ok") is True:
        return result
    if not protocol_mismatch and failure is None:
        return result
    if failure is not None and failure.code not in {"PROTOCOL_MISMATCH", "GATEWAY_REPORTED_FAILURE"}:
        return result
    return stamp_soft_success(result, tool, evidence)


def stamp_soft_success(result: dict[str, Any], tool: str, evidence: dict[str, Any]) -> dict[str, Any]:
    out = dict(result)
    execution = dict(_mapping(out.get("execution")))
    execution["ok"] = True
    execution.setdefault("status", "ui_effect_soft_success")
    execution["softSuccess"] = True
    verification = dict(_mapping(out.get("verification")))
    verification["ok"] = True
    verification["passed"] = True
    verification.setdefault("status", "PAGE_CHANGED" if evidence.get("pageChanged") else "PASSED")
    transport = dict(_mapping(out.get("transport")))
    transport.setdefault("ok", True)
    warning = {
        "code": "PROTOCOL_MISMATCH",
        "layer": "PROTOCOL",
        "message": SOFT_SUCCESS_WARNING,
        "afterPackage": evidence.get("afterPackage"),
        "pageChanged": evidence.get("pageChanged"),
    }
    out.update(
        {
            "protocol_version": out.get("protocol_version") or CAPABILITY_PROTOCOL_VERSION,
            "capability_id": out.get("capability_id") or tool,
            "ok": True,
            "transport": transport,
            "execution": execution,
            "verification": verification,
            "error": None,
            "warning": warning,
            "pageChanged": bool(evidence.get("pageChanged")),
            "afterPackage": evidence.get("afterPackage"),
        }
    )
    return out


def maybe_clear_protocol_mismatch(failure: Failure | None, evidence: dict[str, Any]) -> Failure | None:
    if failure is None:
        return None
    if failure.code == "PROTOCOL_MISMATCH" and evidence.get("matched"):
        return None
    return failure
