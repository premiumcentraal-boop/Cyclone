"""Multi-device AI task runner.

This is the piece that turns the existing fleet plumbing (DeviceFleetManager +
DesktopAgentService, which already address every paired Android device by
`device_id`) into something you can actually hand a goal to: "on device A do
X, on device B do Y, at the same time."

Each task is its own observe -> plan -> act -> verify loop, scoped to exactly
one `device_id`. Several tasks can run concurrently (one thread per task), so
two devices genuinely run independent goals in parallel. Nothing here talks to
Android directly - it reuses `DesktopAgentService.observe()` / `.action()`,
which already carry Android's own verification authority, the freshness
(`expected_observation_id`) contract, and the fleet's device addressing.

Kept deliberately small and dependency-free (stdlib `urllib` only) so it is
easy to read, easy to test without a network, and easy to swap the planner
for something else later.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
import json
import re
import threading
import time
import urllib.request
from typing import Any, Callable, Protocol

from .models import RuntimeErrorCode, now_ms

# Tools the planner is allowed to request. Deliberately a subset of
# ALLOWED_PHONE_TOOLS in agent.py: no workspace management, no raw shell -
# just the ordinary phone actions a task loop needs.
PLANNER_TOOLS = frozenset({
    "phone.click", "phone.long_press", "phone.swipe", "phone.scroll",
    "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
})

# Heuristic consequential-action gate. This mirrors the product's existing
# "explicit approval boundaries for consequential actions" invariant
# (see AGENTS.md) - it is intentionally conservative and keyword-based as a
# first pass, not a replacement for the on-device GATE policy.
_CONSEQUENTIAL_PATTERN = re.compile(
    r"\b(pay|purchase|buy|checkout|send money|transfer|wire|delete account|"
    r"factory reset|uninstall|remove account|confirm order)\b",
    re.IGNORECASE,
)

MAX_TASK_TURNS_DEFAULT = 40
TASK_TIMEOUT_MS_DEFAULT = 5 * 60_000


class TaskStatus(StrEnum):
    RUNNING = "RUNNING"
    COMPLETE = "COMPLETE"
    BLOCKED = "BLOCKED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class TaskRunnerError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class DeviceTask:
    task_id: str
    device_id: str
    goal: str
    model: str
    status: TaskStatus = TaskStatus.RUNNING
    turns: int = 0
    max_turns: int = MAX_TASK_TURNS_DEFAULT
    started_at_ms: int = field(default_factory=now_ms)
    finished_at_ms: int | None = None
    result: str | None = None
    log: list[dict[str, Any]] = field(default_factory=list)
    cancel_event: threading.Event = field(default_factory=threading.Event, repr=False)

    def note(self, kind: str, **extra: Any) -> None:
        self.log.append({"at": now_ms(), "kind": kind, **extra})

    def public(self) -> dict[str, Any]:
        return {
            "taskId": self.task_id,
            "deviceId": self.device_id,
            "goal": self.goal,
            "model": self.model,
            "status": self.status.value,
            "turns": self.turns,
            "maxTurns": self.max_turns,
            "startedAtEpochMs": self.started_at_ms,
            "finishedAtEpochMs": self.finished_at_ms,
            "result": self.result,
            "log": list(self.log[-200:]),
        }


class ActionPlanner(Protocol):
    """Given a goal and the current (compact) observation, decide the next move."""

    def plan(self, goal: str, observation: dict[str, Any], history: list[dict[str, Any]]) -> dict[str, Any]: ...


@dataclass
class PlannedAction:
    directive: str  # "ACT" | "DONE" | "BLOCKED"
    tool: str | None = None
    params: dict[str, Any] | None = None
    reason: str | None = None


def _validate_plan(data: dict[str, Any]) -> PlannedAction:
    """Enforced on every plan the runner acts on, regardless of which
    ActionPlanner produced it. A planner is untrusted input the same way a
    model response is - this must not depend on any one planner implementation
    remembering to check it."""
    directive = str(data.get("directive") or "").upper()
    if directive not in {"ACT", "DONE", "BLOCKED"}:
        raise TaskRunnerError("MALFORMED_PLAN", "Planner directive must be ACT, DONE, or BLOCKED.")
    tool = data.get("tool")
    params = data.get("params") if isinstance(data.get("params"), dict) else {}
    if directive == "ACT":
        if tool not in PLANNER_TOOLS:
            raise TaskRunnerError("MALFORMED_PLAN", f"Planner requested an unavailable tool: {tool!r}")
    return PlannedAction(directive=directive, tool=tool, params=params, reason=data.get("reason"))


def _parse_planned_action(raw: str) -> PlannedAction:
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        raise TaskRunnerError("MALFORMED_PLAN", f"Planner did not return valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise TaskRunnerError("MALFORMED_PLAN", "Planner response must be a JSON object.")
    return _validate_plan(data)


_SYSTEM_PROMPT = (
    "You control a single Android device through a small set of tools. "
    "You are given the goal and a compact description of what is currently on screen. "
    "Reply with EXACTLY one JSON object and nothing else, in the form: "
    '{"directive": "ACT", "tool": "phone.click", "params": {...}, "reason": "why"} '
    "or {\"directive\": \"DONE\", \"reason\": \"why the goal is complete\"} "
    "or {\"directive\": \"BLOCKED\", \"reason\": \"why you cannot continue without a human\"}. "
    f"Allowed tools: {', '.join(sorted(PLANNER_TOOLS))}."
)


class OpenRouterActionPlanner:
    """Calls OpenRouter directly, mirroring the request shape the Android app
    already uses in PortableModelRequest.kt (explicit provider allow-list, no
    invented sampling/reasoning params, no data-collection override)."""

    def __init__(
        self,
        api_key: str,
        model: str,
        providers: list[str],
        *,
        post: Callable[[str, dict[str, str], dict[str, Any]], dict[str, Any]] | None = None,
        timeout_s: float = 30.0,
    ):
        if not api_key:
            raise TaskRunnerError("MISSING_KEY", "An OpenRouter API key is required.")
        if not providers:
            raise TaskRunnerError("NO_PROVIDER", "At least one verified provider endpoint is required.")
        self._api_key = api_key
        self._model = model
        self._providers = list(providers)
        self._post = post or self._http_post
        self._timeout_s = timeout_s

    def _http_post(self, url: str, headers: dict[str, str], body: dict[str, Any]) -> dict[str, Any]:
        data = json.dumps(body).encode("utf-8")
        request = urllib.request.Request(url, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(request, timeout=self._timeout_s) as response:  # noqa: S310
            return json.loads(response.read().decode("utf-8"))

    def plan(self, goal: str, observation: dict[str, Any], history: list[dict[str, Any]]) -> dict[str, Any]:
        messages = [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps({
                "goal": goal,
                "screen": observation,
                "recentActions": history[-6:],
            }, ensure_ascii=False)},
        ]
        body = {
            "model": self._model,
            "messages": messages,
            "stream": False,
            "max_tokens": 1024,
            "provider": {"only": self._providers, "sort": "latency", "allow_fallbacks": False},
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        response = self._post("https://openrouter.ai/api/v1/chat/completions", headers, body)
        try:
            content = response["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise TaskRunnerError("PROVIDER_RESPONSE", f"Unexpected OpenRouter response shape: {exc}") from exc
        parsed = _parse_planned_action(content)
        return {"directive": parsed.directive, "tool": parsed.tool, "params": parsed.params, "reason": parsed.reason}


class MultiDeviceTaskRunner:
    """Owns zero or more concurrently-running DeviceTask loops, one per device_id
    at a time. Reuses the existing DesktopAgentService for every phone call, so
    device pairing, auth, and Android's own verification authority are unchanged."""

    def __init__(
        self,
        agent_service: Any,
        planner_factory: Callable[[str, list[str], str], ActionPlanner],
        *,
        max_workers: int = 8,
        max_turns: int = MAX_TASK_TURNS_DEFAULT,
        task_timeout_ms: int = TASK_TIMEOUT_MS_DEFAULT,
    ):
        self._agent_service = agent_service
        self._planner_factory = planner_factory
        self._max_workers = max(1, max_workers)
        self._max_turns = max_turns
        self._task_timeout_ms = task_timeout_ms
        self._lock = threading.RLock()
        self._tasks: dict[str, DeviceTask] = {}
        self._active_device_ids: set[str] = set()

    def start(
        self, device_id: str, goal: str, *, model: str, providers: list[str], api_key: str,
    ) -> dict[str, Any]:
        goal = goal.strip()
        if not device_id or not goal:
            raise TaskRunnerError(RuntimeErrorCode.INVALID_REQUEST.value, "deviceId and goal are required.")
        with self._lock:
            if device_id in self._active_device_ids:
                raise TaskRunnerError(
                    "DEVICE_BUSY",
                    f"Device {device_id} already has a task running. Cancel it first or wait for it to finish.",
                )
            import secrets
            task = DeviceTask(
                task_id=f"task_{secrets.token_hex(8)}",
                device_id=device_id,
                goal=goal,
                model=model,
                max_turns=self._max_turns,
            )
            self._tasks[task.task_id] = task
            self._active_device_ids.add(device_id)
        # Build the planner (which needs the API key) before the task is
        # registered as startable, but after it's already reserved the device -
        # a bad key fails this one task, not the device's availability for a
        # retried request.
        try:
            planner = self._planner_factory(model, providers, api_key)
        except TaskRunnerError as error:
            self._finish(task, TaskStatus.FAILED, error.message)
            raise
        threading.Thread(target=self._run, args=(task, planner), name=f"cyclone-task-{task.task_id}", daemon=True).start()
        return task.public()

    def start_many(self, requests: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Start one independent task per {deviceId, goal, model, providers, apiKey}
        entry. Devices run genuinely concurrently; a failure starting one task
        does not block the others."""
        started: list[dict[str, Any]] = []
        for item in requests:
            try:
                started.append(self.start(
                    device_id=str(item.get("deviceId") or item.get("device_id") or ""),
                    goal=str(item.get("goal") or ""),
                    model=str(item.get("model") or ""),
                    providers=list(item.get("providers") or []),
                    api_key=str(item.get("apiKey") or item.get("api_key") or ""),
                ))
            except TaskRunnerError as error:
                started.append({
                    "deviceId": item.get("deviceId") or item.get("device_id"),
                    "status": TaskStatus.FAILED.value,
                    "error": {"code": error.code, "message": error.message},
                })
        return started

    def status(self, task_id: str) -> dict[str, Any]:
        with self._lock:
            task = self._tasks.get(task_id)
        if task is None:
            raise TaskRunnerError("NOT_FOUND", f"No task with id {task_id}.")
        return task.public()

    def list_tasks(self) -> list[dict[str, Any]]:
        with self._lock:
            return [t.public() for t in self._tasks.values()]

    def cancel(self, task_id: str) -> dict[str, Any]:
        with self._lock:
            task = self._tasks.get(task_id)
        if task is None:
            raise TaskRunnerError("NOT_FOUND", f"No task with id {task_id}.")
        task.cancel_event.set()
        return task.public()

    def _finish(self, task: DeviceTask, status: TaskStatus, result: str) -> None:
        task.status = status
        task.result = result
        task.finished_at_ms = now_ms()
        with self._lock:
            self._active_device_ids.discard(task.device_id)

    def _run(self, task: DeviceTask, planner: ActionPlanner) -> None:
        history: list[dict[str, Any]] = []
        task.note("started", goal=task.goal, model=task.model)
        try:
            while True:
                if task.cancel_event.is_set():
                    return self._finish(task, TaskStatus.CANCELLED, "Cancelled by user.")
                if now_ms() - task.started_at_ms > self._task_timeout_ms:
                    return self._finish(task, TaskStatus.FAILED, "Task timed out.")
                if task.turns >= task.max_turns:
                    return self._finish(task, TaskStatus.FAILED, "Reached the maximum number of steps.")

                try:
                    observed = self._agent_service.observe(task.device_id)
                except Exception as exc:  # noqa: BLE001 - surfaced into task log, not raised
                    task.note("observe_failed", error=str(exc))
                    return self._finish(task, TaskStatus.FAILED, f"Could not observe device: {exc}")

                observation = observed.get("observation") or {}
                observation_id = (observed.get("witness") or {}).get("observation_id") or ""
                task.note("observed", observationId=observation_id, pageKey=observed.get("witness", {}).get("page_key"))

                if _CONSEQUENTIAL_PATTERN.search(task.goal):
                    task.note("blocked_for_review", reason="Goal looks consequential (payment/delete/etc).")
                    return self._finish(task, TaskStatus.BLOCKED, "This goal needs human approval before Cyclone will act on it.")

                try:
                    raw_plan = planner.plan(task.goal, observation, history)
                    plan = _validate_plan(raw_plan if isinstance(raw_plan, dict) else {})
                except TaskRunnerError as exc:
                    task.note("plan_failed", code=exc.code, error=exc.message)
                    return self._finish(task, TaskStatus.FAILED, f"Planner error: {exc.message}")

                task.note("planned", directive=plan.directive, tool=plan.tool, params=plan.params, reason=plan.reason)

                if plan.directive == "DONE":
                    return self._finish(task, TaskStatus.COMPLETE, plan.reason or "Goal reported complete.")
                if plan.directive == "BLOCKED":
                    return self._finish(task, TaskStatus.BLOCKED, plan.reason or "Model requested a human.")

                tool = plan.tool
                params = plan.params or {}
                try:
                    result = self._agent_service.action(task.device_id, {
                        "tool": tool,
                        "params": params,
                        "goal": task.goal,
                        "expected_observation_id": observation_id,
                    })
                except Exception as exc:  # noqa: BLE001
                    task.note("action_failed", tool=tool, error=str(exc))
                    history.append({"tool": tool, "params": params, "ok": False, "error": str(exc)})
                    task.turns += 1
                    continue

                ok = bool(result.get("ok"))
                task.note("acted", tool=tool, ok=ok, verification=(result.get("verification") or {}).get("status"))
                history.append({"tool": tool, "params": params, "ok": ok})
                task.turns += 1
        finally:
            if task.status == TaskStatus.RUNNING:
                self._finish(task, TaskStatus.FAILED, "Task loop exited unexpectedly.")
