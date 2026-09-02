from __future__ import annotations

import base64
import mimetypes
import os
import re
import time
from pathlib import Path
from typing import Any, Callable

from .compact import compact_element, compact_observation, compact_search, page_changed, page_delta, redact
from .gateway import GatewayClient, GatewayError
from .reports import SessionRecorder
from .protocol import Failure, classify_failure

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
ANDROID_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")

MUTATING_ACTIONS = ALLOWED_ACTIONS - {"phone.wait_for"}
ELEMENT_ID_KEYS = {"elementId", "element_id"}
COORDINATE_KEYS = {
    "x", "y", "x1", "y1", "x2", "y2", "startx", "starty", "endx", "endy",
    "coordinates", "coordinate", "bounds", "point", "points",
}


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


def _element_id_from_params(params: dict[str, Any]) -> str | None:
    direct = next((params.get(key) for key in ELEMENT_ID_KEYS if params.get(key)), None)
    selector = params.get("selector")
    nested = None
    if isinstance(selector, dict):
        nested = next((selector.get(key) for key in ELEMENT_ID_KEYS if selector.get(key)), None)
        unsupported = set(selector) - ELEMENT_ID_KEYS
        if unsupported:
            raise ValueError(
                "MCP selector input must contain only a current observation-scoped elementId; "
                "use phone_locate or phone_ui_search first."
            )
    if direct and nested and str(direct) != str(nested):
        raise ValueError("elementId values disagree")
    element_id = str(direct or nested or "").strip()
    return element_id or None


def _reject_coordinate_params(value: Any, path: str = "params") -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            normalized = key_text.replace("_", "").lower()
            if normalized in COORDINATE_KEYS:
                raise ValueError(
                    f"{path}.{key_text} is not permitted: MCP does not execute free-form coordinates. "
                    "Use a current observation-scoped elementId or a safe typed route."
                )
            _reject_coordinate_params(nested, f"{path}.{key_text}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            _reject_coordinate_params(nested, f"{path}[{index}]")


def _validate_mcp_action_params(tool: str, params: dict[str, Any]) -> str | None:
    """Keep the PC-agent surface semantic and observation-scoped.

    Gateway support for broader selectors remains unchanged for internal Cyclone routes. MCP is a
    deliberately narrower model-facing surface: it never accepts raw text, fuzzy or coordinate
    selectors that can drift after context truncation.
    """
    _validate_typed_params(params)
    _reject_coordinate_params(params)
    element_id = _element_id_from_params(params)

    if tool in {"phone.click", "phone.long_press", "phone.type"} and not element_id:
        raise ValueError(
            f"{tool} requires a current observation-scoped elementId. Run phone_locate or "
            "phone_observe, then use one returned candidate ID."
        )
    if tool == "phone.swipe":
        raise ValueError(
            "phone.swipe has no safe typed MCP route in the frozen gateway protocol. "
            "Use phone.scroll with direction=forward/backward instead."
        )
    if tool == "phone.scroll":
        allowed = {"direction", "selector", *ELEMENT_ID_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.scroll accepts only direction and an optional current elementId")
        direction = str(params.get("direction") or "forward").lower()
        if direction not in {"forward", "backward"}:
            raise ValueError("phone.scroll direction must be forward or backward")
    elif tool == "phone.type":
        allowed = {"value", "text", "selector", *ELEMENT_ID_KEYS}
        if set(params) - allowed or not isinstance(params.get("value", params.get("text")), str):
            raise ValueError("phone.type accepts one text/value and a current elementId")
        if "value" in params and "text" in params and params["value"] != params["text"]:
            raise ValueError("phone.type text and value disagree")
    elif tool == "phone.long_press":
        allowed = {"durationMs", "selector", *ELEMENT_ID_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.long_press accepts only durationMs and a current elementId")
    elif tool == "phone.click":
        allowed = {"selector", *ELEMENT_ID_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.click accepts only a current observation-scoped elementId")
    elif tool in {"phone.back", "phone.home"}:
        if params:
            raise ValueError(f"{tool} accepts no parameters")
    elif tool == "phone.open_app":
        if set(params) != {"package"} or not ANDROID_PACKAGE.fullmatch(str(params.get("package") or "")):
            raise ValueError("phone.open_app requires one valid Android package name")
    elif tool == "phone.wait_for":
        allowed = {"condition", "timeoutMs", "pollMs", "selector", *ELEMENT_ID_KEYS}
        if set(params) - allowed:
            raise ValueError("phone.wait_for accepts only a bounded condition, timeout, poll interval, and elementId")
        condition = params.get("condition")
        if condition is not None and not isinstance(condition, dict):
            raise ValueError("phone.wait_for condition must be an object")
    return element_id


def _error_class(value: Any, fallback: str) -> str:
    failure = classify_failure(value)
    return failure.code if failure else fallback


class PhoneTools:
    def __init__(self, gateway: GatewayClient | None = None, recorder: SessionRecorder | None = None):
        self.gateway = gateway or GatewayClient()
        self.recorder = recorder or SessionRecorder()
        self.last_call_failed = False
        self._page_cards: dict[str, tuple[Any, dict[str, Any]]] = {}
        self._current_element_ids: dict[str, set[str]] = {}

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

    def phone_list(self, args: dict[str, Any]) -> Any:
        return self.phone_devices(args)

    def phone_observe(self, args: dict[str, Any]) -> Any:
        device_id = _device_id(args)
        mode = str(args.get("mode") or "compact")
        include_screenshot = bool(args.get("include_screenshot", False))
        goal = str(args.get("goal") or "").strip()
        if device_id:
            raw = self.gateway.device_observe(device_id, include_screenshot=include_screenshot, mode=mode)
        else:
            raw = self.gateway.observe(include_screenshot=include_screenshot, mode=mode)
        # Classify the complete typed response before compacting away protocol/error layers.
        if classify_failure(raw):
            return redact(raw)
        if mode == "full":
            return redact(raw)
        return self._remember_page_card(device_id, raw, goal=goal)

    def phone_locate(self, args: dict[str, Any]) -> Any:
        """Fuse bounded readiness, Page Card context, and semantic search for one goal."""
        device_id = _device_id(args)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        query = str(args.get("query") or goal).strip()
        if len(query) > 240:
            raise ValueError("query exceeds the bounded length")
        if device_id:
            status = self.gateway.device_status(device_id)
            raw = self.gateway.device_observe(device_id, include_screenshot=False, mode="compact")
        else:
            status = self.gateway.status()
            raw = self.gateway.observe(include_screenshot=False, mode="compact")
        if classify_failure(raw):
            return redact(raw)
        page_card = self._remember_page_card(device_id, raw, goal=goal)
        try:
            search_raw = (
                self.gateway.device_ui_search(device_id, query)
                if device_id else self.gateway.ui_search(query)
            )
            semantic_search = compact_search(search_raw, query=query, goal=goal)
            self._remember_search_ids(device_id, semantic_search)
        except GatewayError as exc:
            semantic_search = {
                "kind": "semantic_search",
                "query": query,
                "available": False,
                "errorClass": _error_class(exc.body, "SEARCH_UNAVAILABLE"),
            }
        return {
            "kind": "phone_locate",
            "goal": goal,
            "status": _compact_status(status),
            "pageCard": page_card,
            "semanticSearch": semantic_search,
            "next": (
                "Use a goal-ranked/current elementId immediately, then call phone_act. "
                "After any mutation, use phone_locate again; IDs are not reusable."
            ),
        }

    def phone_ui_search(self, args: dict[str, Any]) -> Any:
        query = str(args.get("query") or "").strip()
        if not query:
            raise ValueError("query is required")
        goal = str(args.get("goal") or "").strip()
        device_id = _device_id(args)
        if device_id:
            search = compact_search(self.gateway.device_ui_search(device_id, query), query=query, goal=goal)
        else:
            search = compact_search(self.gateway.ui_search(query), query=query, goal=goal)
        self._remember_search_ids(device_id, search)
        return search

    def phone_inspect_element(self, args: dict[str, Any]) -> Any:
        element_id = str(args.get("element_id") or "").strip()
        if not element_id:
            raise ValueError("element_id is required")
        device_id = _device_id(args)
        if device_id:
            return compact_element(self.gateway.device_ui_element(device_id, element_id), element_id=element_id)
        return compact_element(self.gateway.ui_element(element_id), element_id=element_id)

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
        _validate_mcp_action_params(tool, params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.type" and args.get("user_authorized") is not True:
            # This is only an MCP-side intent/UX guard. It is never Android policy authority;
            # the V3 GatewayActionAuthority must still authorize the actual handoff.
            raise ValueError("phone.type requires user_authorized=true as an explicit MCP intent acknowledgement")
        params = _forward_type_authorization(tool, args, params)
        device_id = _device_id(args)
        if tool not in MUTATING_ACTIONS:
            return self._run_non_mutating_action(device_id, tool, params, goal)
        return self._run_verified_mutation(device_id, tool, params, goal)

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
        _validate_mcp_action_params(tool, params)
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

    def _scope_key(self, device_id: str) -> str:
        return device_id or "__gateway_selected__"

    def _remember_page_card(self, device_id: str, raw: Any, *, goal: str = "") -> dict[str, Any]:
        card = compact_observation(raw, goal=goal)
        scope = self._scope_key(device_id)
        self._page_cards[scope] = (raw, card)
        self._current_element_ids[scope] = _card_element_ids(card)
        return card

    def _remember_search_ids(self, device_id: str, search: dict[str, Any]) -> None:
        scope = self._scope_key(device_id)
        if scope not in self._page_cards:
            return
        current = self._current_element_ids.setdefault(scope, set())
        current.update(_search_element_ids(search))

    def _run_non_mutating_action(self, device_id: str, tool: str, params: dict[str, Any], goal: str) -> Any:
        try:
            action = (
                self.gateway.device_action(device_id, tool, params, goal)
                if device_id else self.gateway.action(tool, params, goal)
            )
        except GatewayError as exc:
            return _failed_action_envelope(
                tool=tool,
                goal=goal,
                before=None,
                after=None,
                action=None,
                error_class=_error_class(exc.body, "GATEWAY_ERROR"),
                failure_layer="gateway",
                delta="The non-mutating action did not return a usable result.",
            )
        failure = classify_failure(action)
        return {
            "kind": "phone_action_result",
            "tool": tool,
            "goal": goal,
            "mutating": False,
            "actionStatus": _action_status(action, failure, after_observed=False),
            "beforePageCard": None,
            "afterPageCard": None,
            "pageChanged": None,
            "delta": "No mutation was requested.",
            "errorClass": failure.code if failure else None,
            "ok": failure is None,
            "error": None if failure is None else {"code": failure.code, "layer": failure.layer},
        }

    def _run_verified_mutation(self, device_id: str, tool: str, params: dict[str, Any], goal: str) -> dict[str, Any]:
        scope = self._scope_key(device_id)
        cached = self._page_cards.get(scope)
        if cached is None:
            return _failed_action_envelope(
                tool=tool,
                goal=goal,
                before=None,
                after=None,
                action=None,
                error_class="STALE_OBSERVATION",
                failure_layer="protocol",
                delta="No current Page Card is available. Run phone_locate or phone_observe before acting.",
            )
        element_id = _element_id_from_params(params)
        if element_id and element_id not in self._current_element_ids.get(scope, set()):
            return _failed_action_envelope(
                tool=tool,
                goal=goal,
                before=cached[1],
                after=None,
                action=None,
                error_class="STALE_OBSERVATION",
                failure_layer="protocol",
                delta="The elementId is not from the current Page Card/search scope. Locate again before acting.",
            )
        before_raw, _ = cached
        before = compact_observation(before_raw, goal=goal)
        # The gateway clears its observation authority after an action. Clear our model-facing
        # cache at the same boundary so an ID can never be accidentally reused.
        self._page_cards.pop(scope, None)
        self._current_element_ids.pop(scope, None)
        action: Any = None
        failure = None
        action_error: GatewayError | None = None
        try:
            action = (
                self.gateway.device_action(device_id, tool, params, goal)
                if device_id else self.gateway.action(tool, params, goal)
            )
            failure = classify_failure(action)
        except GatewayError as exc:
            action_error = exc
            action = redact(exc.body) if exc.body is not None else {"error": str(exc)}
            failure = classify_failure(action)

        after: dict[str, Any] | None = None
        after_failure = None
        # A 200 transport receipt never proves that Android changed state. When the gateway is
        # still reachable, observe again even after execution/verification failure for evidence.
        action_error_class = failure.code if failure else ("GATEWAY_ERROR" if action_error else None)
        can_observe_after = action_error_class not in {"DEVICE_DISCONNECTED", "AUTH_REJECTED"}
        if can_observe_after:
            try:
                after_raw = (
                    self.gateway.device_observe(device_id, include_screenshot=False, mode="compact")
                    if device_id else self.gateway.observe(include_screenshot=False, mode="compact")
                )
                after_failure = classify_failure(after_raw)
                if after_failure is None:
                    after = self._remember_page_card(device_id, after_raw, goal=goal)
            except GatewayError as exc:
                after_failure = classify_failure(exc.body)
                if after_failure is None:
                    after_failure = Failure("AFTER_OBSERVATION_FAILED", "transport")

        changed = page_changed(before, after)
        delta = page_delta(before, after, changed)
        typed_verification = _gateway_verification_passed(action)
        already_on_page = (
            _android_execution_ok(action)
            and after is not None
            and changed is False
            and _page_has_goal_label(after, goal)
        )
        if failure is not None and failure.code == "VERIFICATION_FAILED" and already_on_page:
            failure = None
        if not typed_verification and already_on_page:
            typed_verification = True
        verified = failure is None and action_error is None and typed_verification and after is not None
        if failure is not None:
            error_class, failure_layer = failure.code, failure.layer
        elif action_error is not None:
            error_class, failure_layer = "GATEWAY_ERROR", "gateway"
        elif after_failure is not None:
            error_class, failure_layer = after_failure.code, after_failure.layer
        elif not typed_verification:
            error_class, failure_layer = "VERIFICATION_REQUIRED", "verification"
        elif after is None:
            error_class, failure_layer = "AFTER_OBSERVATION_FAILED", "verification"
        else:
            error_class, failure_layer = None, None
        return {
            "kind": "phone_action_result",
            "tool": tool,
            "goal": goal,
            "mutating": True,
            "actionStatus": _action_status(
                action, failure, after_observed=after is not None, verification_passed=typed_verification,
            ),
            "beforePageCard": before,
            "afterPageCard": after,
            "pageChanged": changed,
            "delta": delta,
            "errorClass": error_class,
            "failureLayer": failure_layer,
            "ok": verified,
            "error": None if verified else {"code": error_class, "layer": failure_layer},
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


def _compact_status(status: Any) -> dict[str, Any]:
    """Keep locate useful without returning an unbounded gateway diagnostic object."""
    if not isinstance(status, dict):
        return {"available": False, "summary": "Gateway status was not an object."}
    nested_status = status.get("status") if isinstance(status.get("status"), dict) else {}
    health = status.get("gateway_health") if isinstance(status.get("gateway_health"), dict) else {}
    result: dict[str, Any] = {"available": True}
    for key in (
        "device_id", "deviceId", "serial", "serialSuffix", "model", "state", "ready",
        "cyclone_bridge_reachable", "bridgeReachable", "gatewayEnabled", "accessibilityEnabled",
    ):
        value = status.get(key, nested_status.get(key))
        if isinstance(value, (str, int, float, bool)):
            result[key] = value
    if health:
        result["gatewayHealth"] = {
            key: value for key, value in health.items()
            if key in {"state", "ready", "available"} and isinstance(value, (str, int, float, bool))
        }
    return redact(result)


def _card_element_ids(card: dict[str, Any]) -> set[str]:
    candidates = card.get("candidates") if isinstance(card.get("candidates"), dict) else {}
    rows = list(candidates.get("current") or []) + list(candidates.get("goalRanked") or [])
    return {
        str(item["elementId"]) for item in rows
        if isinstance(item, dict) and isinstance(item.get("elementId"), str) and item["elementId"]
    }


def _search_element_ids(search: dict[str, Any]) -> set[str]:
    return {
        str(item["elementId"]) for item in search.get("results", [])
        if isinstance(item, dict) and isinstance(item.get("elementId"), str) and item["elementId"]
    }


def _forward_type_authorization(tool: str, args: dict[str, Any], params: dict[str, Any]) -> dict[str, Any]:
    """Copy MCP intent flag into Android params. Never echo the typed plaintext here."""
    if tool != "phone.type" or args.get("user_authorized") is not True:
        return params
    forwarded = dict(params)
    forwarded["user_authorized"] = True
    forwarded["userAuthorized"] = True
    return forwarded


def _android_execution_ok(action: Any) -> bool:
    if not isinstance(action, dict):
        return False
    execution = action.get("execution") if isinstance(action.get("execution"), dict) else {}
    if execution.get("ok") is True:
        return True
    nested = execution.get("androidExecution") if isinstance(execution.get("androidExecution"), dict) else {}
    if nested.get("ok") is True:
        return True
    android = action.get("android_execution") if isinstance(action.get("android_execution"), dict) else {}
    return android.get("ok") is True


def _page_has_goal_label(card: dict[str, Any] | None, goal: str) -> bool:
    needle = (goal or "").strip()
    if len(needle) < 2 or not isinstance(card, dict):
        return False
    parts = [
        str(card.get("pageText") or ""),
        str(card.get("pageSummary") or ""),
        str(card.get("title") or ""),
    ]
    location = card.get("location") if isinstance(card.get("location"), dict) else {}
    parts.append(str(location.get("title") or ""))
    candidates = card.get("candidates") if isinstance(card.get("candidates"), dict) else {}
    for row in list(candidates.get("current") or []) + list(candidates.get("goalRanked") or []):
        if isinstance(row, dict):
            parts.append(str(row.get("label") or ""))
    for row in card.get("controls") or []:
        if isinstance(row, dict):
            parts.append(str(row.get("label") or ""))
    return needle.lower() in " ".join(parts).lower()


def _gateway_verification_passed(action: Any) -> bool:
    if not isinstance(action, dict):
        return False
    verification = action.get("verification")
    return (
        isinstance(verification, dict)
        and verification.get("ok") is True
        and verification.get("status") not in {None, "not_required", "required", "failed"}
    )


def _action_status(
    action: Any, failure: Any, *, after_observed: bool, verification_passed: bool | None = None,
) -> dict[str, Any]:
    action = action if isinstance(action, dict) else {}
    transport = action.get("transport") if isinstance(action.get("transport"), dict) else {}
    execution = action.get("execution") if isinstance(action.get("execution"), dict) else {}
    verification = action.get("verification") if isinstance(action.get("verification"), dict) else {}
    gw_passed = _gateway_verification_passed(action) if verification_passed is None else verification_passed
    return {
        "transport": "ok" if transport.get("ok") is True else ("failed" if transport else "unknown"),
        "execution": "ok" if execution.get("ok") is True else ("failed" if execution else "unknown"),
        "gatewayVerification": "passed" if gw_passed else (
            "failed" if verification else "unavailable"
        ),
        "afterObserved": after_observed,
        "verified": failure is None and gw_passed and after_observed,
    }


def _failed_action_envelope(
    *,
    tool: str,
    goal: str,
    before: dict[str, Any] | None,
    after: dict[str, Any] | None,
    action: Any,
    error_class: str,
    failure_layer: str,
    delta: str,
) -> dict[str, Any]:
    return {
        "kind": "phone_action_result",
        "tool": tool,
        "goal": goal,
        "mutating": True,
        "actionStatus": _action_status(action, None, after_observed=False),
        "beforePageCard": before,
        "afterPageCard": after,
        "pageChanged": page_changed(before, after),
        "delta": delta,
        "errorClass": error_class,
        "failureLayer": failure_layer,
        "ok": False,
        "error": {"code": error_class, "layer": failure_layer},
    }


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
