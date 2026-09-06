"""PROTOCOL_MISMATCH is not a task failure when the UI effect is already visible."""
from __future__ import annotations

from typing import Any

from .envelope import canonical_error, extract_android_execution


def package_of(*payloads: Any) -> str:
    for payload in payloads:
        if not isinstance(payload, dict):
            continue
        location = payload.get("location") if isinstance(payload.get("location"), dict) else {}
        after_state = payload.get("afterState") if isinstance(payload.get("afterState"), dict) else {}
        for source in (payload, location, after_state, payload.get("after") if isinstance(payload.get("after"), dict) else {}):
            if not isinstance(source, dict):
                continue
            for key in ("package", "packageName", "afterPackage"):
                value = str(source.get(key) or "").strip()
                if value:
                    return value
    return ""


def page_key_of(*payloads: Any) -> str:
    for payload in payloads:
        if not isinstance(payload, dict):
            continue
        location = payload.get("location") if isinstance(payload.get("location"), dict) else {}
        for source in (payload, location):
            value = str(source.get("pageKey") or source.get("page_key") or "").strip()
            if value:
                return value
    return ""


def ui_effect_matched(
    tool: str,
    params: dict[str, Any] | None,
    *,
    before: Any,
    after: Any,
    execution: Any = None,
) -> dict[str, Any]:
    params = params if isinstance(params, dict) else {}
    after_package = package_of(after, execution)
    before_package = package_of(before)
    after_page = page_key_of(after)
    before_page = page_key_of(before)
    expected = ""
    if tool == "phone.open_app":
        expected = str(params.get("package") or params.get("packageName") or "").strip()
    elif tool == "phone.launch_intent":
        expected = str(params.get("package") or "com.android.vending").strip()
    elif tool == "phone.wait_for":
        condition = params.get("condition") if isinstance(params.get("condition"), dict) else params
        expected = str(condition.get("package") or "").strip()
    page_changed = False
    if isinstance(execution, dict):
        page_changed = execution.get("pageChanged") is True
        verification = execution.get("verification")
        if isinstance(verification, dict) and verification.get("pageChanged") is True:
            page_changed = True
    semantic = bool(
        (after_package and before_package and after_package != before_package)
        or (after_page and before_page and after_page != before_page)
    )
    expected_hit = bool(expected and after_package == expected)
    matched = bool(expected_hit or page_changed or semantic)
    return {
        "matched": matched,
        "pageChanged": page_changed or semantic,
        "afterPackage": after_package or None,
        "beforePackage": before_package or None,
        "expectedPackage": expected or None,
    }


def apply_protocol_mismatch_soft_success(
    *,
    tool: str,
    params: dict[str, Any] | None,
    before: Any,
    after: Any,
    execution: Any,
    execution_ok: bool,
    execution_error_class: str | None,
    verification_passed: bool,
    error: dict[str, Any] | None,
) -> tuple[bool, bool, dict[str, Any] | None, dict[str, Any] | None, str]:
    """Return execution_ok, overall_ok-ready verification, error, warning, status."""
    evidence = ui_effect_matched(tool, params, before=before, after=after, execution=execution)
    warning = None
    if execution_error_class == "PROTOCOL_MISMATCH" and evidence["matched"]:
        warning = {
            **canonical_error(
                "PROTOCOL_MISMATCH",
                "PROTOCOL",
                "Android execution result did not match the capability protocol; the observed UI effect was treated as success.",
            ),
            "afterPackage": evidence.get("afterPackage"),
            "pageChanged": evidence.get("pageChanged"),
        }
        return True, True, None, warning, "ui_effect_soft_success"
    if extract_android_execution(execution) is None and evidence["matched"] and not execution_ok:
        warning = {
            **canonical_error(
                "PROTOCOL_MISMATCH",
                "PROTOCOL",
                "Android execution result did not match the capability protocol; the observed UI effect was treated as success.",
            ),
            "afterPackage": evidence.get("afterPackage"),
            "pageChanged": evidence.get("pageChanged"),
        }
        return True, True, None, warning, "ui_effect_soft_success"
    overall_ok = execution_ok and verification_passed
    return execution_ok, overall_ok, error, warning, ("android_succeeded" if execution_ok else "protocol_mismatch")
