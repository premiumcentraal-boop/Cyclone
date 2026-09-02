from __future__ import annotations

import re
import time
from typing import Any, Callable

from .audit import SafeAuditLog
from .gateway import GatewayClient, GatewayError
from .safe import redact, strip_typed_plaintext, validate_typed_params
from .tool_catalog import ALLOWED_ACTIONS, ALLOWED_GROUP_ACTIONS, TOOL_NAMES
from .phone_mcp import (
    compact_observation,
    draft_run_denied,
    matched_verified_skill,
    skill_run_normalize,
    skill_save_payload,
    skill_save_success,
)


PROVIDER_ID = re.compile(r"^[a-z][a-z0-9_.-]{0,79}$")
IMAGE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.;+:-]{0,239}$")
INSTANCE_ID = re.compile(r"^vdev_[a-f0-9]{16}$")
ROUTINE_ID = re.compile(r"^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
TARGET_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$")


def _required_id(args: dict[str, Any], key: str, pattern: re.Pattern[str]) -> str:
    value = str(args.get(key) or "").strip()
    if not pattern.fullmatch(value):
        raise ValueError(f"{key} is invalid")
    return value


def _only_keys(args: dict[str, Any], allowed: set[str]) -> None:
    if set(args) - allowed:
        raise ValueError("Unexpected parameters are not permitted")


class PhoneTools:
    def __init__(self, gateway: GatewayClient | None = None, audit: SafeAuditLog | None = None):
        self.gateway = gateway or GatewayClient()
        self.audit = audit or SafeAuditLog()

    def call(self, name: str, arguments: dict[str, Any]) -> Any:
        if name not in TOOL_NAMES:
            return {"error": {"code": "UNKNOWN_TOOL", "message": "Unknown Cyclone MCP tool"}}
        started = time.perf_counter()
        ok = False
        error_code: str | None = None
        try:
            method: Callable[[dict[str, Any]], Any] = getattr(self, name)
            result = redact(method(arguments))
            error_code = _error_code(result)
            ok = error_code is None
            return result
        except GatewayError as exc:
            body = redact(exc.body) if exc.body is not None else {"error": {"code": "GATEWAY_ERROR"}}
            error_code = _error_code(body) or "GATEWAY_ERROR"
            return body
        except (ValueError, OSError) as exc:
            error_code = "INVALID_REQUEST"
            return {"error": {"code": error_code, "message": str(exc)[:300]}}
        finally:
            self.audit.record(name, ok=ok, elapsed_ms=int((time.perf_counter() - started) * 1000), error_code=error_code)

    @staticmethod
    def _device(args: dict[str, Any]) -> str | None:
        value = args.get("device_id")
        if value is None:
            return None
        value = str(value).strip()
        return value or None

    def phone_list(self, _: dict[str, Any]) -> Any:
        return {"devices": [device.safe_dict() for device in self.gateway.list_devices()]}

    def phone_status(self, args: dict[str, Any]) -> Any:
        return self.gateway.status(self._device(args))

    def phone_capabilities(self, args: dict[str, Any]) -> Any:
        return self.gateway.capabilities(self._device(args), refresh=bool(args.get("refresh", False)))

    def phone_observe(self, args: dict[str, Any]) -> Any:
        mode = str(args.get("mode") or "compact")
        if mode not in {"compact", "full"}:
            raise ValueError("mode must be compact or full")
        return self.gateway.observe(
            self._device(args),
            include_screenshot=bool(args.get("include_screenshot", False)),
            mode=mode,
        )

    def phone_locate(self, args: dict[str, Any]) -> Any:
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        query = str(args.get("query") or goal).strip()
        device_id = self._device(args)
        status = self.gateway.status(device_id)
        raw = self.gateway.observe(device_id, include_screenshot=False, mode="compact")
        page_card = compact_observation(raw, goal=goal)
        try:
            search_raw = self.gateway.ui_search(query, device_id)
        except GatewayError as exc:
            search_raw = {"available": False, "error": redact(exc.body)}
        matched = None
        skip_model = False
        try:
            match_raw = self.gateway.skill_match(goal, str(page_card.get("pageKey") or ""), device_id)
            matched = matched_verified_skill(match_raw, goal, str(page_card.get("pageKey") or ""))
            skip_model = bool(matched)
        except (GatewayError, AttributeError, TypeError, ValueError, ImportError):
            matched = None
            skip_model = False
        return {
            "kind": "phone_locate",
            "goal": goal,
            "status": status if isinstance(status, dict) else {"available": False},
            "pageCard": page_card,
            "semanticSearch": search_raw,
            "matchedSkill": matched,
            "skipModel": skip_model,
            "next": (
                matched["next"] if matched else
                "Use a goal-ranked/current elementId immediately, then call phone_act. "
                "After any mutation, use phone_locate again; IDs are not reusable."
            ),
        }

    def phone_ui_search(self, args: dict[str, Any]) -> Any:
        query = str(args.get("query") or "").strip()
        if not query:
            raise ValueError("query is required")
        return self.gateway.ui_search(query, self._device(args))

    def phone_inspect_element(self, args: dict[str, Any]) -> Any:
        element_id = str(args.get("element_id") or "").strip()
        if not element_id:
            raise ValueError("element_id is required")
        return self.gateway.ui_element(element_id, self._device(args))

    def phone_screenshot(self, args: dict[str, Any]) -> Any:
        return self.gateway.observe(self._device(args), include_screenshot=True, mode="compact")

    def phone_current_page(self, args: dict[str, Any]) -> Any:
        return self.gateway.current_page(self._device(args))

    def phone_page_history(self, args: dict[str, Any]) -> Any:
        return self.gateway.page_history(self._device(args))

    def phone_act(self, args: dict[str, Any]) -> Any:
        tool = str(args.get("tool") or "")
        if tool not in ALLOWED_ACTIONS:
            raise ValueError(f"Unsupported phone action: {tool}")
        params = args.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params must be an object")
        validate_typed_params(params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        if tool == "phone.type" and args.get("user_authorized") is not True:
            raise ValueError("phone.type requires user_authorized=true; Android policy remains authoritative")
        result = self.gateway.action(tool, params, goal, self._device(args))
        if tool == "phone.type":
            typed = params.get("value") if isinstance(params.get("value"), str) else params.get("text")
            result = strip_typed_plaintext(result, typed if isinstance(typed, str) else None)
        return result

    def phone_skill_save(self, args: dict[str, Any]) -> Any:
        built = skill_save_payload(args)
        if built.get("written") is False:
            return built
        payload = built.get("_compile")
        if not isinstance(payload, dict):
            raise ValueError("skill compile payload is invalid")
        result = self.gateway.skill_save(payload, self._device(args))
        return skill_save_success(result, payload)

    def phone_skill_run(self, args: dict[str, Any]) -> Any:
        skill_id = str(args.get("skill_id") or args.get("skillId") or "").strip()
        if not skill_id:
            raise ValueError("skill_id is required")
        dry_run = bool(args.get("dryRun") or args.get("dry_run"))
        device_id = self._device(args)
        if not dry_run:
            try:
                meta = self.gateway.skill_get(skill_id, device_id)
                skill = meta.get("skill") if isinstance(meta, dict) and isinstance(meta.get("skill"), dict) else meta
                if isinstance(skill, dict) and str(skill.get("status") or "").lower() == "draft":
                    return draft_run_denied(skill_id, "draft")
            except (GatewayError, AttributeError, TypeError, ValueError, KeyError):
                pass
        result = self.gateway.skill_run(
            skill_id, dry_run=dry_run,
            params=args.get("params") if isinstance(args.get("params"), dict) else {},
            device_id=device_id,
        )
        return skill_run_normalize(result, skill_id=skill_id, dry_run=dry_run)

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
        validate_typed_params(params)
        goal = str(args.get("goal") or "").strip()
        if not goal:
            raise ValueError("goal is required")
        results: list[dict[str, Any]] = []
        for device_id in device_ids:
            try:
                before = self.gateway.observe(device_id, include_screenshot=False, mode="compact")
                outcome = self.gateway.action(tool, params, goal, device_id)
                results.append({"device_id": device_id, "ok": _error_code(outcome) is None, "before": before, "outcome": outcome})
            except GatewayError as exc:
                results.append({"device_id": device_id, "ok": False, "error": exc.body or {"code": "GATEWAY_ERROR"}})
        return {
            "operation": "typed_group_action",
            "tool": tool,
            "selected_device_ids": device_ids,
            "ok": all(item["ok"] for item in results),
            "results": results,
        }

    def phone_debug_bundle(self, args: dict[str, Any]) -> Any:
        return self.gateway.debug_bundle(
            self._device(args), expected=str(args.get("expected") or ""), goal=str(args.get("goal") or "")
        )

    def phone_teach_start(self, args: dict[str, Any]) -> Any:
        return self.gateway.teach_start(self._device(args), goal=str(args.get("goal") or ""))

    def phone_teach_status(self, args: dict[str, Any]) -> Any:
        return self.gateway.teach_status(self._device(args))

    def phone_teach_stop(self, args: dict[str, Any]) -> Any:
        return self.gateway.teach_stop(self._device(args), compile_for_review=bool(args.get("compile_for_review", True)))

    def phone_virtual_list(self, _: dict[str, Any]) -> Any:
        _only_keys(_, set())
        return self.gateway.virtual_instances()

    def phone_virtual_create(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"provider", "image"})
        provider = _required_id(args, "provider", PROVIDER_ID)
        image = _required_id(args, "image", IMAGE_ID)
        return self.gateway.virtual_create(provider, image)

    def phone_virtual_start(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return self.gateway.virtual_lifecycle(instance_id, "start")

    def phone_virtual_stop(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"instance_id"})
        instance_id = _required_id(args, "instance_id", INSTANCE_ID)
        return self.gateway.virtual_lifecycle(instance_id, "stop")

    def phone_routine_run(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "routine_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        routine_id = _required_id(args, "routine_id", ROUTINE_ID)
        return self.gateway.routine_run(device_id, routine_id)

    def phone_routine_status(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return self.gateway.routine_status(device_id, run_id)

    def phone_routine_cancel(self, args: dict[str, Any]) -> Any:
        _only_keys(args, {"device_id", "run_id"})
        device_id = _required_id(args, "device_id", TARGET_ID)
        run_id = _required_id(args, "run_id", RUN_ID)
        return self.gateway.routine_cancel(device_id, run_id)


def _error_code(value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    error = value.get("error")
    if isinstance(error, dict) and isinstance(error.get("code"), str):
        return error["code"]
    return None
