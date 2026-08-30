from __future__ import annotations

import base64
import mimetypes
import os
import re
import time
from pathlib import Path
from typing import Any, Callable

from .compact import compact_observation, redact
from .gateway import GatewayClient, GatewayError
from .reports import SessionRecorder
from .protocol import classify_failure

ALLOWED_ACTIONS = {
    "phone.click",
    "phone.long_press",
    "phone.swipe",
    "phone.scroll",
    "phone.type",
    "phone.back",
    "phone.home",
    "phone.open_app",
    "phone.wait_for",
}
ALLOWED_GROUP_ACTIONS = ALLOWED_ACTIONS - {"phone.type"}
FORBIDDEN_OPERATION_KEY = re.compile(
    r"(?i)^(?:cmd|command|shell|adb|powershell|subprocess|executable|script|root|su|docker|host_command)$"
)

FAILURE_CLASSES = {
    "ACCESSIBILITY_PERCEPTION",
    "SEMANTICIZATION_LOSS",
    "AGENT_CONTEXT_TRUNCATION",
    "AGENT_REASONING_OR_MEMORY",
}

PROVIDER_ID = re.compile(r"^[a-z][a-z0-9_.-]{0,79}$")
IMAGE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.;+:-]{0,239}$")
INSTANCE_ID = re.compile(r"^vdev_[a-f0-9]{16}$")
ROUTINE_ID = re.compile(r"^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
TARGET_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")


def _result_failed(result: Any) -> bool:
    return classify_failure(result) is not None


def _device_id(args: dict[str, Any]) -> str:
    return str(args.get("device_id") or "").strip()


def _required_id(args: dict[str, Any], key: str, pattern: re.Pattern[str]) -> str:
    value = str(args.get(key) or "").strip()
    if not pattern.fullmatch(value):
        raise ValueError(f"{key} is invalid")
    return value


def _only_keys(args: dict[str, Any], allowed: set[str]) -> None:
    if set(args) - allowed:
        raise ValueError("Unexpected parameters are not permitted")


def _validate_typed_params(value: Any, path: str = "params") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            key_text = str(key)
            if FORBIDDEN_OPERATION_KEY.fullmatch(key_text):
                raise ValueError(f"{path}.{key_text} is not a permitted typed phone parameter")
            _validate_typed_params(item, f"{path}.{key_text}")
    elif isinstance(value, list):
        if len(value) > 100:
            raise ValueError(f"{path} exceeds the bounded list size")
        for index, item in enumerate(value):
            _validate_typed_params(item, f"{path}[{index}]")


class PhoneTools:
    def __init__(self, gateway: GatewayClient | None = None, recorder: SessionRecorder | None = None):
        self.gateway = gateway or GatewayClient()
        self.recorder = recorder or SessionRecorder()
        self.last_call_failed = False

    def call(self, name: str, arguments: dict[str, Any]) -> list[dict[str, Any]]:
        started = time.perf_counter()
        ok = False
        result: Any = None
        try:
            method: Callable[[dict[str, Any]], Any] = getattr(self, name)
            result = method(arguments)
            ok = not _result_failed(result)
            self.last_call_failed = not ok
            return _to_mcp_content(result)
        except (AttributeError, GatewayError, ValueError, OSError) as exc:
            gateway_body = exc.body if isinstance(exc, GatewayError) else None
            result = {"error": str(exc), "gateway": gateway_body}
            self.last_call_failed = True
            return [{"type": "text", "text": _json_text(result)}]
        finally:
            self.recorder.record(name, arguments, result, ok, int((time.perf_counter() - started) * 1000))

    def phone_status(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_status(device_id))
        return redact(self.gateway.status())

    def phone_capabilities(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        refresh = bool(args.get("refresh", False))
        if device_id:
            return redact(self.gateway.device_capabilities(device_id, refresh=refresh))
        return redact(self.gateway.capabilities(refresh=refresh))

    def phone_devices(self, args: dict[str, Any]) -> Any:
        return redact(self.gateway.devices(scan=bool(args.get("scan", False))))

    def phone_observe(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        mode = str(args.get("mode") or "compact")
        include_screenshot = bool(args.get("include_screenshot", False))
        if device_id:
            raw = self.gateway.device_observe(device_id, include_screenshot=include_screenshot, mode=mode)
        else:
            raw = self.gateway.observe(include_screenshot=include_screenshot, mode=mode)
        # Classify the complete typed response before compacting away protocol/error layers.
        if classify_failure(raw):
            return redact(raw)
        if mode == "full":
            return redact(raw)
        return compact_observation(raw)

    def phone_ui_search(self, args: dict[str, Any]) -> Any:
        query = str(args.get("query") or "").strip()
        if not query:
            raise ValueError("query is required")
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_ui_search(device_id, query))
        return redact(self.gateway.ui_search(query))

    def phone_inspect_element(self, args: dict[str, Any]) -> Any:
        element_id = str(args.get("element_id") or "").strip()
        if not element_id:
            raise ValueError("element_id is required")
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_ui_element(device_id, element_id))
        return redact(self.gateway.ui_element(element_id))

    def phone_screenshot(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            observed = self.gateway.device_observe(device_id, include_screenshot=True, mode="compact")
            compact = compact_observation(observed)
            screenshot = observed.get("screenshot") if isinstance(observed, dict) else None
            available = isinstance(screenshot, dict) and screenshot.get("available") is not False
            result: dict[str, Any] = {
                "observation": compact,
                "screenshot": screenshot,
                "screenshotAvailable": available,
            }
            if not available:
                result["note"] = (
                    "The Desktop agent endpoint returns semantic evidence only. Use the legacy "
                    "single-device surface (omit device_id) for image bytes, or the PC Companion "
                    "live video; a debug bundle remains available for diagnostics."
                )
            return result
        observed = self.gateway.observe(include_screenshot=True, mode="compact")
        compact = compact_observation(observed)
        screenshot = compact.get("screenshot")
        path = _extract_screenshot_path(screenshot)
        result: dict[str, Any] = {"observation": compact, "screenshot": screenshot}
        if path and Path(path).is_file():
            data = Path(path).read_bytes()
            max_bytes = int(os.getenv("CYCLONE_PHONE_MCP_MAX_IMAGE_BYTES", str(8 * 1024 * 1024)))
            if len(data) <= max_bytes:
                mime = mimetypes.guess_type(path)[0] or "image/png"
                result["_mcp_image"] = {"mimeType": mime, "data": base64.b64encode(data).decode("ascii")}
            else:
                result["imageNote"] = f"Screenshot exists but exceeds MCP image limit ({len(data)} > {max_bytes})"
        return result

    def phone_current_page(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_current_page(device_id))
        return redact(self.gateway.current_page())

    def phone_page_history(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_page_history(device_id))
        return redact(self.gateway.page_history())

    def phone_act(self, args: dict[str, Any]) -> Any:
        tool = str(args.get("tool") or "")
        if tool not in ALLOWED_ACTIONS:
            raise ValueError(f"Unsupported phone action: {tool}")
        params = args.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        _validate_typed_params(params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.type" and args.get("user_authorized") is not True:
            # This is only an MCP-side intent/UX guard. It is never Android policy authority;
            # the V3 GatewayActionAuthority must still authorize the actual handoff.
            raise ValueError("phone.type requires user_authorized=true as an explicit MCP intent acknowledgement")
        if tool in {"phone.click", "phone.long_press"} and _has_coordinate_tap(params) and not _vision_fallback(params, args):
            raise ValueError("Coordinate taps require visionFallback=true")
        device_id = _device_id(args)
        if device_id:
            result = redact(self.gateway.device_action(device_id, tool, params, goal))
        else:
            result = redact(self.gateway.action(tool, params, goal))
        envelope = _attach_act_envelope(result)
        failure = classify_failure(result)
        if failure:
            verification = result.get("verification") if isinstance(result, dict) else None
            payload = {
                "error": "Phone action failed",
                "failureLayer": failure.layer,
                "verification": verification,
                "action": result,
            }
            payload.update({key: envelope[key] for key in _ACT_ENVELOPE_KEYS})
            payload["errorClass"] = envelope.get("errorClass") or failure.code
            payload["ok"] = False
            return payload
        return envelope

    def phone_group_act(self, args: dict[str, Any]) -> Any:
        raw_ids = args.get("device_ids")
        if not isinstance(raw_ids, list) or not raw_ids:
            raise ValueError("device_ids must be a non-empty array of explicit Cyclone device ids")
        device_ids = [str(value).strip() for value in raw_ids]
        if any(not value or len(value) > 160 for value in device_ids):
            raise ValueError("device_ids contains an invalid device id")
        if len(device_ids) > 32 or len(set(device_ids)) != len(device_ids):
            raise ValueError("device_ids must contain 1..32 unique explicit targets")
        tool = str(args.get("tool") or "")
        if tool not in ALLOWED_GROUP_ACTIONS:
            raise ValueError(f"Unsupported group phone action: {tool}")
        params = args.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        _validate_typed_params(params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        results: list[dict[str, Any]] = []
        for device_id in device_ids:
            try:
                before = self.gateway.device_observe(device_id, include_screenshot=False, mode="compact")
                outcome = redact(self.gateway.device_action(device_id, tool, params, goal))
                failure = classify_failure(outcome)
                results.append({
                    "device_id": device_id,
                    "ok": failure is None,
                    "before": compact_observation(before),
                    "outcome": outcome,
                    "failure": None if failure is None else {"code": failure.code, "layer": failure.layer},
                })
            except GatewayError as exc:
                results.append({"device_id": device_id, "ok": False, "error": redact(exc.body)})
        return {
            "operation": "typed_group_action",
            "tool": tool,
            "selected_device_ids": device_ids,
            "ok": all(item["ok"] for item in results),
            "results": results,
        }

    def phone_debug_bundle(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        expected = str(args.get("expected") or "")
        goal = str(args.get("goal") or "")
        if device_id:
            result = redact(self.gateway.device_debug_bundle(device_id, expected, goal))
        else:
            result = redact(self.gateway.debug_bundle(expected, goal))
        stage = _find_stage(result)
        if stage and stage not in FAILURE_CLASSES:
            result = dict(result) if isinstance(result, dict) else {"result": result}
            result["diagnosticWarning"] = f"Unknown failure classification: {stage}"
        return result

    def phone_teach_start(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        goal = str(args.get("goal") or "")
        if device_id:
            return redact(self.gateway.device_teach_start(device_id, goal))
        return redact(self.gateway.teach_start(goal))

    def phone_teach_status(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        if device_id:
            return redact(self.gateway.device_teach_status(device_id))
        return redact(self.gateway.teach_status())

    def phone_teach_stop(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        compile_for_review = bool(args.get("compile_for_review", True))
        if device_id:
            return redact(self.gateway.device_teach_stop(device_id, compile_for_review))
        return redact(self.gateway.teach_stop(compile_for_review))

    def phone_virtual_list(self, _: dict[str, Any]) -> Any:
        _only_keys(_, set())
        return redact(self.gateway.virtual_instances())

    def phone_virtual_create(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"provider", "image"})
        provider = _required_id(args, "provider", PROVIDER_ID)
        image = _required_id(args, "image", IMAGE_ID)
        return redact(self.gateway.virtual_create(provider, image))

    def phone_virtual_start(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return redact(self.gateway.virtual_lifecycle(instance_id, "start"))

    def phone_virtual_stop(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return redact(self.gateway.virtual_lifecycle(instance_id, "stop"))

    def phone_routine_run(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "routine_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        routine_id = _required_id(args, "routine_id", ROUTINE_ID)
        return redact(self.gateway.routine_run(device_id, routine_id))

    def phone_routine_status(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return redact(self.gateway.routine_status(device_id, run_id))

    def phone_routine_cancel(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return redact(self.gateway.routine_cancel(device_id, run_id))


def _find_stage(value: Any) -> str | None:
    if isinstance(value, dict):
        for key in ("stage", "failureClassification", "failure_classification"):
            if isinstance(value.get(key), str):
                return value[key]
        for nested in value.values():
            stage = _find_stage(nested)
            if stage:
                return stage
    return None


def _extract_screenshot_path(value: Any) -> str | None:
    if isinstance(value, str):
        return value if os.path.isabs(value) else None
    if isinstance(value, dict):
        for key in ("path", "file", "localPath", "local_path"):
            path = value.get(key)
            if isinstance(path, str) and os.path.isabs(path):
                return path
    return None


_ACT_ENVELOPE_KEYS = ("ok", "pageChanged", "before", "after", "delta", "errorClass", "generation")
_PASSWORD_HINTS = ("password", "passwd", "passcode", "otp", "pin")


def _has_coordinate_tap(params: dict[str, Any]) -> bool:
    selector = params.get("selector") if isinstance(params.get("selector"), dict) else {}
    return any(key in params for key in ("x", "y")) or any(key in selector for key in ("x", "y"))


def _vision_fallback(params: dict[str, Any], args: dict[str, Any]) -> bool:
    return bool(
        params.get("visionFallback")
        or params.get("vision_fallback")
        or args.get("visionFallback")
        or args.get("vision_fallback")
    )


def _page_ref(value: Any, *, include_card: bool = False) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {"pageKey": None, "package": None, "activity": None}
    nested = value.get("observation") if isinstance(value.get("observation"), dict) else value
    ref = {
        "pageKey": nested.get("pageKey") or nested.get("page_key") or value.get("pageKey") or value.get("page_key"),
        "package": nested.get("package") or value.get("package"),
        "activity": nested.get("activity") or value.get("activity"),
    }
    if include_card:
        card = value.get("pageCard") or nested.get("pageCard")
        if not isinstance(card, dict):
            card = {
                "package": ref["package"],
                "activity": ref["activity"],
                "pageKey": ref["pageKey"],
                "title": nested.get("pageTitle") or nested.get("title") or value.get("title"),
                "pageText": nested.get("pageText") or "",
                "pageSummary": nested.get("pageSummary") or "",
                "controls": nested.get("controls") or nested.get("semanticControls") or [],
            }
        ref["pageCard"] = _sanitize_page_card(card)
    return ref


def _sanitize_page_card(card: Any) -> dict[str, Any]:
    if not isinstance(card, dict):
        return {}
    out = dict(card)
    controls = out.get("controls")
    sanitized_controls: list[dict[str, Any]] = []
    if isinstance(controls, list):
        for item in controls:
            if not isinstance(item, dict):
                continue
            control = dict(item)
            haystack = " ".join(str(control.get(key) or "") for key in ("role", "class", "inputType", "hint", "label", "resourceId")).lower()
            secret = control.get("password") is True or any(hint in haystack for hint in _PASSWORD_HINTS)
            if secret:
                for key in ("text", "label", "value", "hint", "contentDescription"):
                    if key in control and control[key] not in (None, "", "<redacted>"):
                        control[key] = "<redacted>"
            sanitized_controls.append(control)
    out["controls"] = sanitized_controls
    out["pageText"] = out.get("pageText") or ""
    return redact(out)


def _delta(value: Any) -> dict[str, list[Any]]:
    if not isinstance(value, dict):
        return {"appeared": [], "disappeared": [], "focused": []}
    def _list(key: str) -> list[Any]:
        raw = value.get(key)
        return list(raw) if isinstance(raw, list) else []
    return {"appeared": _list("appeared"), "disappeared": _list("disappeared"), "focused": _list("focused")}


def _attach_act_envelope(result: Any) -> dict[str, Any]:
    payload = dict(result) if isinstance(result, dict) else {"ok": False, "errorClass": "PROTOCOL_MISMATCH"}
    before_src = payload.get("before")
    after_src = payload.get("after")
    if not isinstance(before_src, dict):
        before_src = payload.get("before_witness") or {}
    if not isinstance(after_src, dict):
        after_src = payload.get("after_witness") or payload.get("after") or {}
    error_class = payload.get("errorClass") or payload.get("error_class")
    if not error_class and isinstance(payload.get("error"), dict):
        error_class = payload["error"].get("code")
    ok = payload.get("ok")
    if not isinstance(ok, bool):
        ok = payload.get("success") is True
    before = _page_ref(before_src)
    after = _page_ref(after_src, include_card=True)
    page_changed = payload.get("pageChanged")
    if not isinstance(page_changed, bool):
        page_changed = bool(before.get("pageKey") and after.get("pageKey") and before.get("pageKey") != after.get("pageKey"))
    generation = payload.get("generation")
    if generation in (None, ""):
        if isinstance(payload.get("before_witness"), dict):
            generation = payload["before_witness"].get("observation_id")
        elif isinstance(payload.get("witness"), dict):
            generation = payload["witness"].get("observation_id")
    payload.update({
        "ok": bool(ok),
        "pageChanged": bool(page_changed),
        "before": before,
        "after": after,
        "delta": _delta(payload.get("delta")),
        "errorClass": None if error_class in (None, "") else str(error_class),
        "generation": generation,
    })
    return payload


def _to_mcp_content(result: Any) -> list[dict[str, Any]]:
    if isinstance(result, dict) and "_mcp_image" in result:
        safe = dict(result)
        image = safe.pop("_mcp_image")
        return [
            {"type": "text", "text": _json_text(safe)},
            {"type": "image", "data": image["data"], "mimeType": image["mimeType"]},
        ]
    return [{"type": "text", "text": _json_text(result)}]


def _json_text(value: Any) -> str:
    import json
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
