"""Multi-device AI task runner (V5).

Turns the fleet's existing per-device addressing into something you can hand
a goal to: "on device A do X, on device B do Y" - both running at the same
time, each as its own observe -> plan -> act -> verify loop.

Ported against this codebase's real action() contract (not assumed):
- every mutating action must carry `request_ai_control: true`, because V5
  requires an explicit input-ownership handoff before an automation can act;
- if the phone is locked, `action()` raises PHONE_LOCKED and refuses to take
  ownership - this loop treats that as an expected pause, not a crash, and
  never attempts to work around it;
- if a human currently owns input (they're using the phone), `action()`
  raises HUMAN_HAS_CONTROL - same treatment: pause and say so;
- `expected_observation_id` is still required on every mutating call (the
  "fresh observation" freshness contract is unchanged from V4);
- `witness.observation_id` / `witness.page_key` and `result["ok"]` /
  `result["verification"]["status"]` are all still the right field names.

Runs on the default-foreground plane by default. Session-kernel VD (plane 2,
a background display that doesn't interrupt whatever the person is looking
at) is supported by passing `session_id`/`display_id` through to start() -
left as an explicit opt-in here rather than assumed on, since this fleet has
no physical device attached to confirm the workspace.register handshake
against real hardware.
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

from .models import DesktopRuntimeError, RuntimeErrorCode, now_ms

PLANNER_TOOLS = frozenset({
    "phone.click", "phone.long_press", "phone.swipe", "phone.scroll",
    "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
})

_CONSEQUENTIAL_PATTERN = re.compile(
    r"\b(pay|purchase|buy|checkout|send money|transfer|wire|delete account|"
    r"factory reset|uninstall|remove account|confirm order)\b",
    re.IGNORECASE,
)

MAX_TASK_TURNS_DEFAULT = 40
TASK_TIMEOUT_MS_DEFAULT = 5 * 60_000

# Error codes from action()/observe() that mean "pause and tell the human
# why", never "retry silently" and never "work around it". PHONE_LOCKED in
# particular is a deliberate product boundary: Cyclone will not and cannot
# unlock a phone, by design.
_PAUSE_NOT_FAIL_CODES = {
    RuntimeErrorCode.PHONE_LOCKED.value: "This phone is locked. Unlock it, then resume the task.",
    RuntimeErrorCode.HUMAN_HAS_CONTROL.value: "Someone is actively using this phone right now.",
}


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
    session_id: str | None = None
    display_id: int | None = None
    mission_id: str | None = None  # links this device's task to a shared cross-device run

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
            "missionId": self.mission_id,
        }


class ActionPlanner(Protocol):
    def plan(self, goal: str, observation: dict[str, Any], history: list[dict[str, Any]]) -> dict[str, Any]: ...


@dataclass
class PlannedAction:
    directive: str  # "ACT" | "DONE" | "BLOCKED"
    tool: str | None = None
    params: dict[str, Any] | None = None
    reason: str | None = None


def _validate_plan(data: dict[str, Any]) -> PlannedAction:
    directive = str(data.get("directive") or "").upper()
    if directive not in {"ACT", "DONE", "BLOCKED"}:
        raise TaskRunnerError("MALFORMED_PLAN", "Planner directive must be ACT, DONE, or BLOCKED.")
    tool = data.get("tool")
    params = data.get("params") if isinstance(data.get("params"), dict) else {}
    if directive == "ACT" and tool not in PLANNER_TOOLS:
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
    "You are given the goal, a compact description of what is currently on screen, and "
    "(if present) shared notes other devices in this mission have already written down. "
    "Reply with EXACTLY one JSON object and nothing else, in the form: "
    '{"directive": "ACT", "tool": "phone.click", "params": {...}, "reason": "why"} '
    "or {\"directive\": \"DONE\", \"reason\": \"why the goal is complete\"} "
    "or {\"directive\": \"BLOCKED\", \"reason\": \"why you cannot continue without a human\"}. "
    f"Allowed tools: {', '.join(sorted(PLANNER_TOOLS))}."
)


class OpenRouterActionPlanner:
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
                "goal": goal, "screen": observation, "recentActions": history[-6:],
            }, ensure_ascii=False)},
        ]
        body = {
            "model": self._model, "messages": messages, "stream": False, "max_tokens": 1024,
            "provider": {"only": self._providers, "sort": "latency", "allow_fallbacks": False},
        }
        headers = {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}
        response = self._post("https://openrouter.ai/api/v1/chat/completions", headers, body)
        try:
            content = response["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise TaskRunnerError("PROVIDER_RESPONSE", f"Unexpected OpenRouter response shape: {exc}") from exc
        parsed = _parse_planned_action(content)
        return {"directive": parsed.directive, "tool": parsed.tool, "params": parsed.params, "reason": parsed.reason}


class MissionScratchpad:
    """A small shared notes area for one multi-device mission. One device's
    task can leave a note (e.g. "verification code: 481223") and another
    device's task, in the same mission, reads it on its next planning step.
    In-memory only, mission-scoped, discarded with the mission - never
    written to Brain/learning stores, matching the single-device desk's own
    privacy rule."""

    def __init__(self):
        self._lock = threading.RLock()
        self._notes: dict[str, list[dict[str, Any]]] = {}

    def write(self, mission_id: str, device_id: str, note: str) -> None:
        note = note.strip()[:2000]
        if not note:
            return
        with self._lock:
            self._notes.setdefault(mission_id, []).append({
                "at": now_ms(), "deviceId": device_id, "note": note,
            })

    def read(self, mission_id: str | None) -> list[dict[str, Any]]:
        if not mission_id:
            return []
        with self._lock:
            return list(self._notes.get(mission_id, []))

    def clear(self, mission_id: str) -> None:
        with self._lock:
            self._notes.pop(mission_id, None)


class MultiDeviceTaskRunner:
    def __init__(
        self,
        agent_service: Any,
        planner_factory: Callable[[str, list[str], str], ActionPlanner],
        *,
        max_workers: int = 8,
        max_turns: int = MAX_TASK_TURNS_DEFAULT,
        task_timeout_ms: int = TASK_TIMEOUT_MS_DEFAULT,
        scratchpad: MissionScratchpad | None = None,
    ):
        self._agent_service = agent_service
        self._planner_factory = planner_factory
        self._max_workers = max(1, max_workers)
        self._max_turns = max_turns
        self._task_timeout_ms = task_timeout_ms
        self._lock = threading.RLock()
        self._tasks: dict[str, DeviceTask] = {}
        self._active_device_ids: set[str] = set()
        self.scratchpad = scratchpad or MissionScratchpad()

    def start(
        self, device_id: str, goal: str, *, model: str, providers: list[str], api_key: str,
        mission_id: str | None = None, session_id: str | None = None, display_id: int | None = None,
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
                task_id=f"task_{secrets.token_hex(8)}", device_id=device_id, goal=goal, model=model,
                max_turns=self._max_turns, session_id=session_id, display_id=display_id, mission_id=mission_id,
            )
            self._tasks[task.task_id] = task
            self._active_device_ids.add(device_id)
        try:
            planner = self._planner_factory(model, providers, api_key)
        except TaskRunnerError as error:
            self._finish(task, TaskStatus.FAILED, error.message)
            raise
        threading.Thread(target=self._run, args=(task, planner), name=f"cyclone-task-{task.task_id}", daemon=True).start()
        return task.public()

    def start_many(self, requests: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """One independent task per {deviceId, goal, model, providers, apiKey,
        missionId?} entry. All requests in one call that share a missionId can
        read/write the same scratchpad, so device B can react to what device A
        found. Devices run genuinely concurrently."""
        started: list[dict[str, Any]] = []
        for item in requests:
            try:
                started.append(self.start(
                    device_id=str(item.get("deviceId") or item.get("device_id") or ""),
                    goal=str(item.get("goal") or ""),
                    model=str(item.get("model") or ""),
                    providers=list(item.get("providers") or []),
                    api_key=str(item.get("apiKey") or item.get("api_key") or ""),
                    mission_id=item.get("missionId") or item.get("mission_id"),
                    session_id=item.get("sessionId") or item.get("session_id"),
                    display_id=item.get("displayId") or item.get("display_id"),
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

    def list_tasks(self, mission_id: str | None = None) -> list[dict[str, Any]]:
        with self._lock:
            tasks = list(self._tasks.values())
        if mission_id:
            tasks = [t for t in tasks if t.mission_id == mission_id]
        return [t.public() for t in tasks]

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

    def _identity_payload(self, task: DeviceTask) -> dict[str, Any]:
        if task.session_id:
            return {"sessionId": task.session_id, "displayId": task.display_id if task.display_id is not None else 0}
        return {}

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
                    observed = self._agent_service.observe(task.device_id, payload=self._identity_payload(task))
                except DesktopRuntimeError as exc:
                    if exc.code in _PAUSE_NOT_FAIL_CODES:
                        task.note("paused", code=exc.code)
                        return self._finish(task, TaskStatus.BLOCKED, _PAUSE_NOT_FAIL_CODES[exc.code])
                    task.note("observe_failed", code=exc.code, error=exc.safe_message)
                    return self._finish(task, TaskStatus.FAILED, f"Could not observe device: {exc.safe_message}")
                except Exception as exc:  # noqa: BLE001
                    task.note("observe_failed", error=str(exc))
                    return self._finish(task, TaskStatus.FAILED, f"Could not observe device: {exc}")

                observation = observed.get("observation") or {}
                observation_id = (observed.get("witness") or {}).get("observation_id") or ""
                task.note("observed", observationId=observation_id, pageKey=(observed.get("witness") or {}).get("page_key"))

                if _CONSEQUENTIAL_PATTERN.search(task.goal):
                    task.note("blocked_for_review", reason="Goal looks consequential (payment/delete/etc).")
                    return self._finish(task, TaskStatus.BLOCKED, "This goal needs human approval before Cyclone will act on it.")

                shared_notes = self.scratchpad.read(task.mission_id)
                try:
                    raw_plan = planner.plan(task.goal, {**observation, "missionNotes": shared_notes}, history)
                    plan = _validate_plan(raw_plan if isinstance(raw_plan, dict) else {})
                except TaskRunnerError as exc:
                    task.note("plan_failed", code=exc.code, error=exc.message)
                    return self._finish(task, TaskStatus.FAILED, f"Planner error: {exc.message}")

                task.note("planned", directive=plan.directive, tool=plan.tool, params=plan.params, reason=plan.reason)

                if plan.directive == "DONE":
                    if task.mission_id and plan.reason:
                        self.scratchpad.write(task.mission_id, task.device_id, plan.reason)
                    return self._finish(task, TaskStatus.COMPLETE, plan.reason or "Goal reported complete.")
                if plan.directive == "BLOCKED":
                    return self._finish(task, TaskStatus.BLOCKED, plan.reason or "Model requested a human.")

                tool = plan.tool
                params = plan.params or {}
                action_payload = {
                    "tool": tool, "params": params, "goal": task.goal,
                    "expected_observation_id": observation_id, "request_ai_control": True,
                    **self._identity_payload(task),
                }
                try:
                    result = self._agent_service.action(task.device_id, action_payload)
                except DesktopRuntimeError as exc:
                    if exc.code in _PAUSE_NOT_FAIL_CODES:
                        task.note("paused", code=exc.code, tool=tool)
                        return self._finish(task, TaskStatus.BLOCKED, _PAUSE_NOT_FAIL_CODES[exc.code])
                    task.note("action_failed", tool=tool, code=exc.code, error=exc.safe_message)
                    history.append({"tool": tool, "params": params, "ok": False, "error": exc.safe_message})
                    task.turns += 1
                    continue
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
