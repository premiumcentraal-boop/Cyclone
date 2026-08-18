"""Mobilerun Portal compatibility backend for Cyclone phone tools.

This module is a clean interoperability layer written against Mobilerun
Portal's documented HTTP API.  It intentionally does not copy Portal source
code.  Mobilerun Portal is currently AGPL-3.0-or-later; keeping it as an
external companion process/app avoids silently changing Cyclone's licensing
while still letting Cyclone use Portal as a battle-tested Android backend.

Cyclone owns the public ``phone.*`` contract.  Backends translate that stable
contract into their native transport.  The existing Cyclone Android app is one
backend; this module is another.
"""

from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass
from difflib import SequenceMatcher
import hashlib
import json
import struct
import time
from typing import Any, Literal
from urllib.parse import urlparse

import httpx
from pydantic import BaseModel, Field, field_validator


class PortalBackendError(RuntimeError):
    """Typed transport/protocol failure from the external Portal."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class PhoneToolRequest(BaseModel):
    id: str
    tool: str
    params: dict[str, Any] = Field(default_factory=dict)


class PhoneToolError(BaseModel):
    code: str
    message: str


class PhoneToolResult(BaseModel):
    commandId: str
    tool: str
    ok: bool
    startedAtMs: int
    finishedAtMs: int
    durationMs: int
    attempts: int = 1
    beforeFingerprint: str | None = None
    afterFingerprint: str | None = None
    payload: Any = None
    error: PhoneToolError | None = None
    backend: str = "mobilerun_portal"


class PortalOwnershipRequest(BaseModel):
    owner: Literal["human", "agent"]


class PortalStatusResponse(BaseModel):
    configured: bool
    reachable: bool
    backend: str = "mobilerun_portal"
    version: str | None = None
    detail: str | None = None


class PortalSettings(BaseModel):
    base_url: str
    token: str
    timeout_seconds: float = 7.0

    @field_validator("base_url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("Mobilerun Portal URL must be an absolute http(s) URL")
        return value.rstrip("/")


@dataclass(frozen=True)
class _Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return max(0, self.right - self.left)

    @property
    def height(self) -> int:
        return max(0, self.bottom - self.top)

    @property
    def center(self) -> tuple[float, float]:
        return self.left + self.width / 2.0, self.top + self.height / 2.0

    def contains(self, x: int, y: int) -> bool:
        return self.left <= x <= self.right and self.top <= y <= self.bottom

    def to_json(self) -> dict[str, int]:
        return {
            "left": self.left,
            "top": self.top,
            "right": self.right,
            "bottom": self.bottom,
            "width": self.width,
            "height": self.height,
        }


_MUTATING_TOOLS = {
    "phone.click",
    "phone.long_press",
    "phone.tap",
    "phone.type",
    "phone.replace_text",
    "phone.scroll",
    "phone.swipe",
    "phone.back",
    "phone.home",
    "phone.open_app",
    "phone.open_notification",
    "phone.set_clipboard",
    "phone.share",
    "phone.launch_intent",
}


class MobilerunPortalClient:
    """Translate Cyclone ``phone.*`` requests into Mobilerun Portal HTTP calls.

    The client intentionally enforces Cyclone's controller lock above Portal:
    even if Portal itself is reachable, mutating calls are rejected while the
    human owns input.  Returning control to the agent requires a fresh
    ``phone.observe`` before another mutation can execute.
    """

    def __init__(
        self,
        settings: PortalSettings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.base_url,
            headers={"Authorization": f"Bearer {settings.token}"},
            timeout=settings.timeout_seconds,
            transport=transport,
        )
        self._controller: Literal["human", "agent"] = "agent"
        self._fresh_observation_required = False
        self._command_cache: dict[str, PhoneToolResult] = {}
        self._recent_actions: dict[str, float] = {}
        self._lock = asyncio.Lock()

    @property
    def controller(self) -> str:
        return self._controller

    async def close(self) -> None:
        await self._client.aclose()

    def set_controller(self, owner: Literal["human", "agent"]) -> None:
        if owner == self._controller:
            return
        self._controller = owner
        if owner == "agent":
            self._fresh_observation_required = True
        else:
            # A human takeover invalidates any server-side assumptions about
            # the page that existed before ownership changed.
            self._fresh_observation_required = False
        self._recent_actions.clear()

    async def status(self) -> PortalStatusResponse:
        try:
            await self._get("/ping", auth=False)
            version = await self._get("/version")
            return PortalStatusResponse(
                configured=True,
                reachable=True,
                version=str(version) if version is not None else None,
                detail="Portal responded to /ping.",
            )
        except Exception as error:
            return PortalStatusResponse(
                configured=True,
                reachable=False,
                detail=str(error),
            )

    async def execute(self, request: PhoneToolRequest) -> PhoneToolResult:
        async with self._lock:
            cached = self._command_cache.get(request.id)
            if cached is not None:
                return cached

            started = _now_ms()
            before = await self._best_effort_fingerprint()

            if request.tool in _MUTATING_TOOLS and self._controller != "agent":
                return self._finish(
                    request,
                    started,
                    before,
                    before,
                    error=PhoneToolError(
                        code="HUMAN_HAS_CONTROL",
                        message="Human currently owns phone input.",
                    ),
                )
            if request.tool in _MUTATING_TOOLS and self._fresh_observation_required:
                return self._finish(
                    request,
                    started,
                    before,
                    before,
                    error=PhoneToolError(
                        code="FRESH_OBSERVATION_REQUIRED",
                        message="Run phone.observe after returning control before issuing actions.",
                    ),
                )
            if request.tool in _MUTATING_TOOLS and self._duplicate_action(request):
                return self._finish(
                    request,
                    started,
                    before,
                    before,
                    error=PhoneToolError(
                        code="DUPLICATE_ACTION",
                        message="Rapid duplicate action suppressed.",
                    ),
                )

            try:
                payload, attempts = await self._dispatch(request)
                after = await self._best_effort_fingerprint()
                result = self._finish(request, started, before, after, payload=payload, attempts=attempts)
            except PortalBackendError as error:
                after = await self._best_effort_fingerprint()
                result = self._finish(
                    request,
                    started,
                    before,
                    after,
                    error=PhoneToolError(code=error.code, message=error.message),
                )
            except Exception as error:  # Defensive boundary: never leak a half-shaped tool result.
                after = await self._best_effort_fingerprint()
                result = self._finish(
                    request,
                    started,
                    before,
                    after,
                    error=PhoneToolError(code="INTERNAL_ERROR", message=str(error)),
                )
            self._remember(result)
            return result

    async def _dispatch(self, request: PhoneToolRequest) -> tuple[Any, int]:
        tool = request.tool
        params = request.params
        if tool == "phone.observe":
            raw = await self._get("/state_full", params={"filter": "false"})
            snapshot = normalize_portal_state(raw, controller=self._controller)
            self._fresh_observation_required = False
            return snapshot, 1
        if tool == "phone.get_current_app":
            raw = await self._get("/phone_state")
            state = _unwrap_mapping(raw)
            return {
                "package": state.get("packageName"),
                "class": state.get("activityName"),
                "app": state.get("currentApp"),
                "controller": self._controller,
            }, 1
        if tool == "phone.capabilities":
            return await self._capabilities(), 1
        if tool == "phone.screenshot":
            png = await self._get_binary("/screenshot", params={"hideOverlay": str(params.get("hideOverlay", True)).lower()})
            width, height = _png_size(png)
            payload: dict[str, Any] = {
                "bytes": len(png),
                "width": width,
                "height": height,
                "sha256": hashlib.sha256(png).hexdigest(),
            }
            if params.get("includeBase64", False):
                payload["pngBase64"] = base64.b64encode(png).decode("ascii")
            return payload, 1
        if tool == "phone.find":
            snapshot = normalize_portal_state(
                await self._get("/state_full", params={"filter": "false"}),
                controller=self._controller,
            )
            selector = params.get("selector") or params
            matches = find_nodes(snapshot, selector, limit=int(params.get("limit", 20)))
            return matches, 1
        if tool == "phone.click":
            return await self._selector_action(request, action="click")
        if tool == "phone.long_press":
            # Portal's documented HTTP surface does not expose a dedicated
            # long-press operation. Do not fake success using an undocumented
            # gesture quirk.
            raise PortalBackendError("CAPABILITY_UNAVAILABLE", "Portal HTTP backend has no documented long-press method.")
        if tool == "phone.tap":
            await self._post("/tap", {"x": params.get("x"), "y": params.get("y")})
            return {"performed": True}, 1
        if tool in {"phone.type", "phone.replace_text"}:
            return await self._type_text(request)
        if tool == "phone.scroll":
            return await self._selector_action(request, action="scroll")
        if tool == "phone.swipe":
            await self._post(
                "/swipe",
                {
                    "startX": params.get("x1"),
                    "startY": params.get("y1"),
                    "endX": params.get("x2"),
                    "endY": params.get("y2"),
                    "duration": params.get("durationMs", 350),
                },
            )
            return {"performed": True}, 1
        if tool == "phone.back":
            await self._post("/global", {"action": 1})
            return {"performed": True}, 1
        if tool == "phone.home":
            await self._post("/global", {"action": 2})
            return {"performed": True}, 1
        if tool == "phone.open_app":
            package = str(params.get("package") or "").strip()
            if not package:
                raise PortalBackendError("INVALID_REQUEST", "package is required")
            await self._post(
                "/app",
                {
                    "package": package,
                    "activity": params.get("activity", ""),
                    "stopBeforeLaunch": str(bool(params.get("stopBeforeLaunch", False))).lower(),
                },
            )
            return {"package": package, "launched": True}, 1
        if tool == "phone.launch_intent":
            uri = str(params.get("uri") or "").strip()
            if not uri:
                raise PortalBackendError("INVALID_REQUEST", "uri is required")
            scheme = urlparse(uri).scheme.lower()
            if scheme not in {"http", "https", "geo", "mailto", "tel", "sms", "market"}:
                raise PortalBackendError("SECURITY_RESTRICTION", f"URI scheme '{scheme}' is not allowed by Cyclone")
            await self._post(
                "/app/deep-link",
                {
                    "deepLink": uri,
                    "displayId": int(params.get("displayId", 0)),
                    "package": params.get("package", ""),
                    "action": params.get("action", "android.intent.action.VIEW"),
                },
            )
            return {"uri": uri, "started": True}, 1
        if tool == "phone.get_clipboard":
            raw = await self._get("/clipboard/get")
            if isinstance(raw, dict):
                text = raw.get("text", raw.get("result", ""))
            else:
                text = raw
            return {"text": "" if text is None else str(text)}, 1
        if tool == "phone.set_clipboard":
            await self._post("/clipboard/set", {"text": str(params.get("text", ""))})
            return {"updated": True}, 1
        if tool in {"phone.wait_for", "phone.assert"}:
            return await self._wait_or_assert(request)
        if tool in {"phone.open_notification", "phone.get_notifications", "phone.share"}:
            raise PortalBackendError(
                "CAPABILITY_UNAVAILABLE",
                f"{tool} is not exposed by the documented Portal HTTP API; use Cyclone native mobile events for this capability.",
            )
        raise PortalBackendError("UNKNOWN_TOOL", f"Unknown phone tool: {tool}")

    async def _selector_action(self, request: PhoneToolRequest, *, action: str) -> tuple[Any, int]:
        params = request.params
        retries = max(0, min(int(params.get("retries", 1)), 3))
        selector = params.get("selector") or params
        attempts = 0
        last_error = "Element not found"
        for attempt in range(retries + 1):
            attempts = attempt + 1
            snapshot = normalize_portal_state(
                await self._get("/state_full", params={"filter": "false"}),
                controller=self._controller,
            )
            matches = find_nodes(snapshot, selector, limit=1)
            if not matches:
                last_error = "Selector did not match the current Portal accessibility tree"
                await asyncio.sleep(0.08 * attempts)
                continue
            node = matches[0]["node"]
            bounds = node["bounds"]
            if action == "click":
                x = bounds["left"] + bounds["width"] / 2
                y = bounds["top"] + bounds["height"] / 2
                await self._post("/tap", {"x": x, "y": y})
            elif action == "scroll":
                direction = str(params.get("direction", "forward")).lower()
                x = bounds["left"] + bounds["width"] / 2
                top = bounds["top"] + max(12, int(bounds["height"] * 0.2))
                bottom = bounds["bottom"] - max(12, int(bounds["height"] * 0.2))
                if direction == "backward":
                    start_y, end_y = top, bottom
                else:
                    start_y, end_y = bottom, top
                await self._post(
                    "/swipe",
                    {"startX": x, "startY": start_y, "endX": x, "endY": end_y, "duration": 320},
                )
            expected = params.get("expect")
            if expected:
                ok, detail = await self._evaluate_condition(expected)
                if not ok:
                    raise PortalBackendError("ASSERTION_FAILED", detail)
            return {"performed": True, "target": node}, attempts
        raise PortalBackendError("ELEMENT_NOT_FOUND", last_error)

    async def _type_text(self, request: PhoneToolRequest) -> tuple[Any, int]:
        params = request.params
        selector = params.get("selector")
        if selector:
            snapshot = normalize_portal_state(
                await self._get("/state_full", params={"filter": "false"}),
                controller=self._controller,
            )
            matches = find_nodes(snapshot, selector, limit=1)
            if not matches:
                raise PortalBackendError("ELEMENT_NOT_FOUND", "Text target selector did not match")
            bounds = matches[0]["node"]["bounds"]
            await self._post(
                "/tap",
                {"x": bounds["left"] + bounds["width"] / 2, "y": bounds["top"] + bounds["height"] / 2},
            )
        value = str(params.get("value", ""))
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        clear = request.tool == "phone.replace_text" or bool(params.get("clear", False))
        await self._post("/keyboard/input", {"base64_text": encoded, "clear": str(clear).lower()})
        return {"performed": True, "characters": len(value)}, 1

    async def _wait_or_assert(self, request: PhoneToolRequest) -> tuple[Any, int]:
        params = request.params
        condition = params.get("condition") or params
        assert_only = request.tool == "phone.assert"
        timeout_ms = 0 if assert_only else max(0, min(int(params.get("timeoutMs", 6000)), 30000))
        poll_ms = max(50, min(int(params.get("pollMs", 120)), 1000))
        deadline = time.monotonic() + timeout_ms / 1000.0
        attempts = 0
        while True:
            attempts += 1
            ok, detail = await self._evaluate_condition(condition)
            if ok:
                return {"matched": True}, attempts
            if assert_only or time.monotonic() >= deadline:
                raise PortalBackendError("ASSERTION_FAILED" if assert_only else "TIMEOUT", detail)
            await asyncio.sleep(poll_ms / 1000.0)

    async def _evaluate_condition(self, condition: dict[str, Any]) -> tuple[bool, str]:
        snapshot = normalize_portal_state(
            await self._get("/state_full", params={"filter": "false"}),
            controller=self._controller,
        )
        kind = str(condition.get("type", "selector_exists"))
        if kind in {"selector_exists", "selector_absent"}:
            selector = condition.get("selector") or condition
            exists = bool(find_nodes(snapshot, selector, limit=1))
            wanted = kind == "selector_exists"
            return exists == wanted, "Selector presence did not match expected condition"
        if kind == "package_equals":
            wanted = condition.get("package")
            return snapshot.get("package") == wanted, f"Current package {snapshot.get('package')} != {wanted}"
        if kind == "text_contains":
            query = str(condition.get("text", "")).casefold()
            found = any(
                query in str(node.get("text", "")).casefold()
                or query in str(node.get("contentDescription", "")).casefold()
                for node in snapshot["nodes"]
            )
            return found, f"Text '{condition.get('text', '')}' was not found"
        if kind == "fingerprint_changed":
            old = str(condition.get("from", ""))
            return snapshot.get("fingerprint") != old, "Screen fingerprint has not changed"
        return False, f"Unknown condition type {kind}"

    async def _capabilities(self) -> list[dict[str, str | None]]:
        status = await self.status()
        portal = "AVAILABLE" if status.reachable else "TEMPORARILY_UNAVAILABLE"
        return [
            {"name": "portal", "status": portal, "detail": status.detail},
            {"name": "accessibility", "status": portal, "detail": "Provided by external Mobilerun Portal service"},
            {"name": "screenshot", "status": portal, "detail": "Portal /screenshot endpoint"},
            {"name": "clipboard", "status": portal, "detail": "Portal keyboard/clipboard integration may require its IME"},
            {"name": "notifications", "status": "UNSUPPORTED_ON_DEVICE", "detail": "Live events require Portal WebSocket/reverse connection; list/open is not in HTTP v1"},
            {"name": "calendar", "status": "UNSUPPORTED_ON_DEVICE", "detail": "Keep Cyclone native Calendar provider for this capability"},
            {"name": "root", "status": "UNSUPPORTED_ON_DEVICE", "detail": "Portal compatibility backend is non-root"},
        ]

    async def _best_effort_fingerprint(self) -> str | None:
        try:
            raw = await self._get("/state_full", params={"filter": "false"})
            return normalize_portal_state(raw, controller=self._controller).get("fingerprint")
        except Exception:
            return None

    async def _get(self, path: str, *, params: dict[str, Any] | None = None, auth: bool = True) -> Any:
        headers = None if auth else {"Authorization": ""}
        response = await self._client.get(path, params=params, headers=headers)
        return _decode_response(response)

    async def _get_binary(self, path: str, *, params: dict[str, Any] | None = None) -> bytes:
        response = await self._client.get(path, params=params)
        if response.status_code >= 400:
            _decode_response(response)  # raises a useful PortalBackendError
        return bytes(response.content)

    async def _post(self, path: str, data: dict[str, Any]) -> Any:
        clean = {key: value for key, value in data.items() if value is not None and value != ""}
        response = await self._client.post(path, data=clean)
        return _decode_response(response)

    def _duplicate_action(self, request: PhoneToolRequest) -> bool:
        now = time.monotonic()
        signature = f"{request.tool}|{json.dumps(request.params, sort_keys=True, default=str)}"
        previous = self._recent_actions.get(signature)
        self._recent_actions[signature] = now
        self._recent_actions = {key: stamp for key, stamp in self._recent_actions.items() if now - stamp < 5.0}
        return previous is not None and now - previous < 0.35

    def _finish(
        self,
        request: PhoneToolRequest,
        started: int,
        before: str | None,
        after: str | None,
        *,
        payload: Any = None,
        error: PhoneToolError | None = None,
        attempts: int = 1,
    ) -> PhoneToolResult:
        finished = _now_ms()
        return PhoneToolResult(
            commandId=request.id,
            tool=request.tool,
            ok=error is None,
            startedAtMs=started,
            finishedAtMs=finished,
            durationMs=finished - started,
            attempts=attempts,
            beforeFingerprint=before,
            afterFingerprint=after,
            payload=payload,
            error=error,
        )

    def _remember(self, result: PhoneToolResult) -> None:
        self._command_cache[result.commandId] = result
        if len(self._command_cache) > 250:
            oldest = next(iter(self._command_cache))
            self._command_cache.pop(oldest, None)


def _decode_response(response: httpx.Response) -> Any:
    if response.status_code >= 400:
        detail = response.text[:500] or f"HTTP {response.status_code}"
        raise PortalBackendError("PORTAL_HTTP_ERROR", detail)
    content_type = response.headers.get("content-type", "").lower()
    if "image/" in content_type or "application/octet-stream" in content_type:
        return bytes(response.content)
    try:
        data = response.json()
    except ValueError:
        text = response.text
        return _maybe_json(text)
    if isinstance(data, dict) and "status" in data:
        status = str(data.get("status", "")).lower()
        if status not in {"success", "ok"}:
            message = data.get("error") or data.get("message") or data.get("result") or "Portal command failed"
            raise PortalBackendError("PORTAL_ERROR", str(message))
        return _maybe_json(data.get("result"))
    return data


def _maybe_json(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    stripped = value.strip()
    if not stripped or stripped[:1] not in {"{", "["}:
        return value
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        return value


def _unwrap_mapping(raw: Any) -> dict[str, Any]:
    raw = _maybe_json(raw)
    if isinstance(raw, dict):
        return raw
    raise PortalBackendError("PORTAL_PROTOCOL_ERROR", "Portal returned a non-object state payload")


def normalize_portal_state(raw: Any, *, controller: str = "agent") -> dict[str, Any]:
    """Convert Portal ``state_full`` into Cyclone's normalized UI snapshot."""
    source = _unwrap_mapping(raw)
    phone = _unwrap_mapping(source.get("phone_state", {})) if source.get("phone_state") is not None else {}
    root = source.get("a11y_tree")
    roots: list[dict[str, Any]]
    if isinstance(root, list):
        roots = [item for item in root if isinstance(item, dict)]
    elif isinstance(root, dict):
        roots = [root]
    else:
        roots = []

    nodes: list[dict[str, Any]] = []

    def walk(node: dict[str, Any], path: str, parent_id: str | None, depth: int) -> None:
        bounds = _extract_bounds(node)
        node_id = str(node.get("uniqueId") or _stable_node_id(path, node, bounds))
        children = [item for item in node.get("children", []) if isinstance(item, dict)]
        child_ids = [
            str(child.get("uniqueId") or _stable_node_id(f"{path}/{index}", child, _extract_bounds(child)))
            for index, child in enumerate(children)
        ]
        class_name = str(node.get("className") or "")
        text = str(node.get("text") or "")
        description = str(node.get("contentDescription") or "")
        snapshot = {
            "id": node_id,
            "path": path,
            "parentId": parent_id,
            "childIds": child_ids,
            "depth": depth,
            "windowId": int(node.get("windowId", 0) or 0),
            "class": class_name,
            "role": _infer_role(node),
            "text": text,
            "contentDescription": description,
            "resourceId": str(node.get("resourceId") or ""),
            "bounds": bounds.to_json(),
            "clickable": bool(node.get("isClickable", False)),
            "longClickable": bool(node.get("isLongClickable", False)),
            "editable": bool(node.get("isEditable", False)),
            "scrollable": bool(node.get("isScrollable", False)),
            "enabled": bool(node.get("isEnabled", True)),
            "selected": bool(node.get("isSelected", False)),
            "checked": bool(node.get("isChecked", False)),
            "checkable": bool(node.get("isCheckable", False)),
            "focused": bool(node.get("isFocused", False)),
            "focusable": bool(node.get("isFocusable", False)),
            "visibleToUser": bool(node.get("isVisibleToUser", True)),
        }
        nodes.append(snapshot)
        for index, child in enumerate(children):
            walk(child, f"{path}/{index}", node_id, depth + 1)

    for index, item in enumerate(roots):
        walk(item, str(index), None, 0)

    width, height = _screen_size(source.get("device_context"), nodes)
    package_name = phone.get("packageName") or (nodes[0].get("packageName") if nodes else None)
    activity = phone.get("activityName") or None
    fingerprint = _screen_fingerprint(str(package_name or ""), nodes)
    return {
        "package": package_name,
        "class": activity,
        "screen": {"width": width, "height": height},
        "timestampMs": _now_ms(),
        "fingerprint": fingerprint,
        "controller": controller,
        "windows": [],
        "nodes": nodes,
        "backend": "mobilerun_portal",
        "deviceContext": source.get("device_context") or {},
    }


def find_nodes(snapshot: dict[str, Any], selector: dict[str, Any], *, limit: int = 20) -> list[dict[str, Any]]:
    nodes = list(snapshot.get("nodes") or [])
    by_id = {str(node.get("id")): node for node in nodes}
    query = dict(selector or {})
    matches: list[tuple[float, dict[str, Any], list[str]]] = []
    for node in nodes:
        score = 0.0
        reasons: list[str] = []
        if query.get("resourceId") is not None:
            if str(node.get("resourceId")) != str(query["resourceId"]):
                continue
            score += 5.0
            reasons.append("resourceId")
        if query.get("text") is not None:
            if str(node.get("text", "")).casefold() != str(query["text"]).casefold():
                continue
            score += 4.0
            reasons.append("text")
        if query.get("textContains") is not None:
            if str(query["textContains"]).casefold() not in str(node.get("text", "")).casefold():
                continue
            score += 3.0
            reasons.append("textContains")
        if query.get("contentDescription") is not None:
            if str(node.get("contentDescription", "")).casefold() != str(query["contentDescription"]).casefold():
                continue
            score += 4.0
            reasons.append("contentDescription")
        if query.get("contentDescriptionContains") is not None:
            if str(query["contentDescriptionContains"]).casefold() not in str(node.get("contentDescription", "")).casefold():
                continue
            score += 3.0
            reasons.append("contentDescriptionContains")
        if query.get("class") is not None and str(node.get("class")) != str(query["class"]):
            continue
        if query.get("class") is not None:
            score += 2.0
            reasons.append("class")
        if query.get("role") is not None and str(node.get("role")) != str(query["role"]):
            continue
        if query.get("role") is not None:
            score += 2.0
            reasons.append("role")
        for key in ("clickable", "editable", "scrollable"):
            if key in query and bool(node.get(key)) != bool(query[key]):
                break
        else:
            pass
        if any(key in query and bool(node.get(key)) != bool(query[key]) for key in ("clickable", "editable", "scrollable")):
            continue
        if "x" in query and "y" in query:
            bounds = _bounds_from_normalized(node.get("bounds") or {})
            if not bounds.contains(int(query["x"]), int(query["y"])):
                continue
            score += 2.0
            reasons.append("coordinate")
        if query.get("ancestorText"):
            wanted = str(query["ancestorText"]).casefold()
            parent_id = node.get("parentId")
            found = False
            while parent_id:
                parent = by_id.get(str(parent_id))
                if parent is None:
                    break
                if wanted in _node_text(parent).casefold():
                    found = True
                    break
                parent_id = parent.get("parentId")
            if not found:
                continue
            score += 1.5
            reasons.append("ancestorText")
        if query.get("descendantText"):
            wanted = str(query["descendantText"]).casefold()
            stack = list(node.get("childIds") or [])
            found = False
            seen: set[str] = set()
            while stack:
                child_id = str(stack.pop())
                if child_id in seen:
                    continue
                seen.add(child_id)
                child = by_id.get(child_id)
                if child is None:
                    continue
                if wanted in _node_text(child).casefold():
                    found = True
                    break
                stack.extend(child.get("childIds") or [])
            if not found:
                continue
            score += 1.5
            reasons.append("descendantText")
        if query.get("fuzzyText"):
            needle = str(query["fuzzyText"]).casefold()
            hay = _node_text(node).casefold()
            ratio = SequenceMatcher(a=needle, b=hay).ratio() if hay else 0.0
            minimum = float(query.get("minFuzzyScore", 0.72))
            if ratio < minimum:
                continue
            score += ratio
            reasons.append(f"fuzzy:{ratio:.2f}")
        if not query:
            score = 0.1
        matches.append((score, node, reasons))
    matches.sort(key=lambda item: (-item[0], int(item[1].get("depth", 0))))
    return [
        {"score": score, "reasons": reasons, "node": node}
        for score, node, reasons in matches[: max(1, min(limit, 100))]
    ]


def _extract_bounds(node: dict[str, Any]) -> _Bounds:
    raw = node.get("boundsInScreen") or node.get("bounds") or {}
    if isinstance(raw, dict):
        return _Bounds(
            int(raw.get("left", 0) or 0),
            int(raw.get("top", 0) or 0),
            int(raw.get("right", 0) or 0),
            int(raw.get("bottom", 0) or 0),
        )
    if isinstance(raw, str):
        try:
            values = [int(part.strip()) for part in raw.split(",")]
            if len(values) == 4:
                return _Bounds(*values)
        except ValueError:
            pass
    return _Bounds(0, 0, 0, 0)


def _bounds_from_normalized(raw: dict[str, Any]) -> _Bounds:
    return _Bounds(int(raw.get("left", 0)), int(raw.get("top", 0)), int(raw.get("right", 0)), int(raw.get("bottom", 0)))


def _stable_node_id(path: str, node: dict[str, Any], bounds: _Bounds) -> str:
    raw = "|".join(
        [
            path,
            str(node.get("resourceId") or ""),
            str(node.get("className") or ""),
            f"{bounds.left},{bounds.top},{bounds.right},{bounds.bottom}",
        ]
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def _infer_role(node: dict[str, Any]) -> str:
    cls = str(node.get("className") or "").casefold()
    if bool(node.get("isEditable")) or "edittext" in cls:
        return "textbox"
    if "switch" in cls:
        return "switch"
    if "checkbox" in cls or bool(node.get("isCheckable")):
        return "checkbox"
    if "button" in cls or bool(node.get("isClickable")) and bool(str(node.get("text") or node.get("contentDescription") or "")):
        return "button"
    if "image" in cls:
        return "image"
    if bool(node.get("isScrollable")):
        return "scroll_container"
    if "textview" in cls:
        return "text"
    return "generic"


def _screen_size(device_context: Any, nodes: list[dict[str, Any]]) -> tuple[int, int]:
    context = device_context if isinstance(device_context, dict) else {}
    candidates = [context]
    for key in ("screen", "display", "device", "displayMetrics"):
        value = context.get(key)
        if isinstance(value, dict):
            candidates.append(value)
    for candidate in candidates:
        width = candidate.get("width") or candidate.get("screenWidth") or candidate.get("displayWidth")
        height = candidate.get("height") or candidate.get("screenHeight") or candidate.get("displayHeight")
        if isinstance(width, (int, float)) and isinstance(height, (int, float)) and width > 0 and height > 0:
            return int(width), int(height)
    width = max((int((node.get("bounds") or {}).get("right", 0)) for node in nodes), default=0)
    height = max((int((node.get("bounds") or {}).get("bottom", 0)) for node in nodes), default=0)
    return width, height


def _screen_fingerprint(package_name: str, nodes: list[dict[str, Any]]) -> str:
    pieces = [package_name]
    for node in nodes[:800]:
        if not node.get("visibleToUser", True):
            continue
        bounds = node.get("bounds") or {}
        pieces.extend(
            [
                str(node.get("resourceId", "")),
                str(node.get("text", ""))[:120],
                str(node.get("contentDescription", ""))[:120],
                str(node.get("class", "")),
                f"{bounds.get('left', 0)},{bounds.get('top', 0)},{bounds.get('right', 0)},{bounds.get('bottom', 0)}",
            ]
        )
    return hashlib.sha256("|".join(pieces).encode("utf-8")).hexdigest()[:20]


def _node_text(node: dict[str, Any]) -> str:
    return " ".join(
        part for part in [str(node.get("text", "")), str(node.get("contentDescription", ""))] if part
    )


def _png_size(data: bytes) -> tuple[int | None, int | None]:
    # PNG signature + IHDR length/type + width/height.
    if len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR":
        return struct.unpack(">II", data[16:24])
    return None, None


def _now_ms() -> int:
    return int(time.time() * 1000)
