"""Fleet controller: registry, per-device queues, bounded parallel missions."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
import json
import os
import re
import secrets
import threading
import time
from pathlib import Path
from typing import Any, Callable

from ..models import DesktopRuntimeError, RuntimeErrorCode, now_ms
from .context import DeviceExecutionContext, assert_context, bind_context
from .planner import FleetPlan, FleetPlanner, PlanMission
from .power import DevicePowerController, ScreenState
from .redact import redact_objective, scrub
from .registry import DeviceRegistry, TrustState
from .resolver import DeviceNameResolver
from .runner import ExecutionHooks, GoalResult, GoalRunner

_PRIORITIES = {
    "USER_INTERACTIVE": 0,
    "HIGH": 1,
    "NORMAL": 2,
    "LOW": 3,
    "BACKGROUND": 4,
}
_ACTIVE = {"QUEUED", "RUNNING", "CANCELLING"}
_TERMINAL = {"COMPLETED", "FAILED", "CANCELLED", "STOPPED"}


@dataclass
class DeviceMission:
    mission_id: str
    fleet_mission_id: str
    device_id: str
    session_id: str
    display_id: int
    objective: str
    priority: str
    depends_on: list[str] = field(default_factory=list)
    status: str = "QUEUED"
    created_at_ms: int = field(default_factory=now_ms)
    updated_at_ms: int = field(default_factory=now_ms)
    step: int = 0
    result_summary: str | None = None
    failure_code: str | None = None
    reobserve_required: bool = False
    verified: bool = False
    applied_action_ids: list[str] = field(default_factory=list)
    handoff: str | None = None
    capabilities: list[str] = field(default_factory=list)
    last_observation: dict[str, Any] | None = None
    cancel: threading.Event = field(default_factory=threading.Event, repr=False)

    def context(self, *, step_id: str = "", action_id: str = "") -> DeviceExecutionContext:
        return bind_context(
            device_id=self.device_id,
            session_id=self.session_id,
            display_id=self.display_id,
            mission_id=self.mission_id,
            fleet_mission_id=self.fleet_mission_id,
            step_id=step_id,
            action_id=action_id,
        )

    def public(self) -> dict[str, Any]:
        return scrub({
            "missionId": self.mission_id,
            "fleetMissionId": self.fleet_mission_id,
            "deviceId": self.device_id,
            "sessionId": self.session_id,
            "displayId": self.display_id,
            "objective": self.objective,
            "priority": self.priority,
            "status": self.status,
            "step": self.step,
            "dependsOn": list(self.depends_on),
            "createdAt": self.created_at_ms,
            "updatedAt": self.updated_at_ms,
            "result": self.result_summary,
            "failureCode": self.failure_code,
            "verified": self.verified,
            "reobserveRequired": self.reobserve_required,
            "appliedActionIds": list(self.applied_action_ids),
            "handoff": self.handoff,
            "capabilities": list(self.capabilities),
            "lastVerifiedState": (self.last_observation or {}).get("state") or (self.last_observation or {}).get("package"),
        })


@dataclass
class FleetMission:
    fleet_mission_id: str
    goal: str
    child_ids: list[str]
    status: str = "QUEUED"
    created_at_ms: int = field(default_factory=now_ms)
    updated_at_ms: int = field(default_factory=now_ms)
    message: str = ""

    def public(self, children: list[DeviceMission]) -> dict[str, Any]:
        return scrub({
            "fleetMissionId": self.fleet_mission_id,
            "goal": self.goal,
            "status": self.status,
            "message": self.message,
            "createdAt": self.created_at_ms,
            "updatedAt": self.updated_at_ms,
            "missions": [child.public() for child in children],
        })


class FleetController:
    def __init__(
        self,
        *,
        registry: DeviceRegistry | None = None,
        registry_path: Path | None = None,
        journal_path: Path | None = None,
        runner: GoalRunner | None = None,
        power: DevicePowerController | None = None,
        planner: FleetPlanner | None = None,
        max_workers: int = 4,
        session_for_device: Callable[[str], tuple[str, int]] | None = None,
    ):
        self.registry = registry or DeviceRegistry(registry_path)
        self.journal_path = journal_path
        self.runner = runner
        self.resolver = DeviceNameResolver()
        self.planner = planner or FleetPlanner(self.registry, self.resolver)
        self.power = power or DevicePowerController(
            self._default_probe,
            record_lookup=self._record_or_none,
        )
        self.max_workers = max(1, min(int(max_workers), 8))
        self._session_for_device = session_for_device or (lambda _device_id: ("default-foreground", 0))
        self._lock = threading.RLock()
        self._device_locks: dict[str, threading.Lock] = {}
        self._missions: dict[str, DeviceMission] = {}
        self._fleet: dict[str, FleetMission] = {}
        self._queues: dict[str, list[str]] = {}
        self._inflight: dict[str, str | None] = {}
        self._control: dict[str, str] = {}
        self._online: dict[str, bool] = {}
        self._named_sessions: dict[str, str] = {}
        self._events: list[dict[str, Any]] = []
        self._pending_confirmation: FleetPlan | None = None
        self._selected_device_id: str | None = None
        self._executor = ThreadPoolExecutor(max_workers=self.max_workers, thread_name_prefix="cyclone-fleet-mission")
        self._alive = True
        self._concurrency = 0
        self._peak_concurrency = 0
        self._concurrency_lock = threading.Lock()
        self._load_journal()

    def shutdown(self) -> None:
        self.stop_all()
        self._alive = False
        self._executor.shutdown(wait=False, cancel_futures=True)

    def adopt(
        self,
        device_id: str,
        *,
        name: str | None = None,
        model: str | None = None,
        manufacturer: str | None = None,
        role: str | None = None,
        trust: bool = False,
        capabilities: dict[str, bool] | None = None,
        secure_lock: bool | None = None,
        insecure_lock_dismiss: bool | None = None,
        online: bool = True,
        aliases: list[str] | None = None,
        battery: int | None = None,
        current_app: str | None = None,
    ) -> dict[str, Any]:
        record = self.registry.upsert_discovered(
            device_id,
            model=model,
            manufacturer=manufacturer,
            online=online,
            capabilities=capabilities,
            battery=battery,
            current_app=current_app,
        )
        if name:
            self.registry.rename(device_id, name, aliases)
        elif aliases:
            self.registry.rename(device_id, record.display_name, aliases)
        if role or secure_lock is not None or insecure_lock_dismiss is not None:
            self.registry.set_profile(
                device_id,
                role=role,
                secure_lock=secure_lock,
                insecure_lock_dismiss=insecure_lock_dismiss,
            )
        if trust:
            self.registry.trust(device_id)
        self._online[device_id] = online
        self._emit("DEVICE_DISCOVERED", device_id=device_id)
        if trust:
            self._emit("DEVICE_TRUSTED", device_id=device_id)
        return self.registry.get(device_id).public()

    def pair(self, device_id: str) -> dict[str, Any]:
        record = self.registry.pair(device_id)
        self._emit("DEVICE_CONNECTED", device_id=device_id, status="PAIRED")
        return record.public()

    def trust(self, device_id: str) -> dict[str, Any]:
        record = self.registry.trust(device_id)
        self._emit("DEVICE_TRUSTED", device_id=device_id)
        return record.public()

    def revoke(self, device_id: str) -> dict[str, Any]:
        record = self.registry.revoke(device_id)
        self._emit("DEVICE_REVOKED", device_id=device_id)
        with self._lock:
            for mission in self._missions.values():
                if mission.device_id == device_id and mission.status in _ACTIVE | {"PAUSED", "WAITING_OWNER", "RECONNECTING"}:
                    mission.cancel.set()
                    self._set_status(mission, "STOPPED", failure_code="REVOKED", summary="Device trust was revoked.")
        return record.public()

    def rename(self, device_id: str, name: str, aliases: list[str] | None = None) -> dict[str, Any]:
        return self.registry.rename(device_id, name, aliases).public()

    def forget(self, device_id: str) -> None:
        self.stop_device(device_id)
        self.registry.forget(device_id)

    def designate_controller(self, device_id: str) -> dict[str, Any]:
        return self.registry.designate_controller(device_id).public()

    def select(self, device_id: str) -> None:
        self.registry.get(device_id)
        self._selected_device_id = device_id

    def take_control(self, device_id: str) -> dict[str, Any]:
        self.registry.get(device_id)
        with self._lock:
            self._control[device_id] = "HUMAN"
            touched = []
            for mission in self._missions.values():
                if mission.device_id == device_id and mission.status in {"QUEUED", "RUNNING", "RECONNECTING"}:
                    mission.cancel.set()
                    self._set_status(mission, "PAUSED", failure_code="HUMAN_CONTROL", summary="Owner has this device.")
                    touched.append(mission.mission_id)
                    self._emit("MISSION_PAUSED", device_id=device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
        return {"deviceId": device_id, "inputOwner": "HUMAN", "pausedMissionIds": touched}

    def release_control(self, device_id: str) -> dict[str, Any]:
        self.registry.get(device_id)
        resumed: list[str] = []
        with self._lock:
            self._control[device_id] = "AGENT"
            for mission in list(self._missions.values()):
                if mission.device_id == device_id and mission.status == "PAUSED" and mission.failure_code == "HUMAN_CONTROL":
                    mission.reobserve_required = True
                    mission.cancel = threading.Event()
                    self._set_status(mission, "QUEUED", failure_code=None, summary="Re-observing before resume.")
                    self._queues.setdefault(device_id, []).append(mission.mission_id)
                    resumed.append(mission.mission_id)
        self._pump()
        return {"deviceId": device_id, "inputOwner": "AGENT", "resumedMissionIds": resumed}

    def note_disconnect(self, device_id: str) -> None:
        already_offline = self._online.get(device_id) is False
        self._online[device_id] = False
        try:
            self.registry.mark_offline(device_id)
        except KeyError:
            pass
        if not already_offline:
            self._emit("DEVICE_DISCONNECTED", device_id=device_id)
        with self._lock:
            for mission in self._missions.values():
                if mission.device_id != device_id or mission.status not in {"RUNNING", "QUEUED"}:
                    continue
                mission.reobserve_required = True
                if mission.status == "QUEUED":
                    self._set_status(mission, "RECONNECTING", failure_code="DISCONNECTED", summary="Device disconnected before the action.")
                # RUNNING missions notice via hooks.online() and must not be replayed.

    def note_reconnect(self, device_id: str, *, boot_id: str | None = None) -> None:
        was_online = self._online.get(device_id) is True
        self._online[device_id] = True
        try:
            record = self.registry.get(device_id)
            previous_boot = record.boot_id
            self.registry.mark_online(device_id)
            if boot_id is not None:
                self.registry.upsert_discovered(device_id, boot_id=boot_id, online=True)
                if previous_boot not in (None, boot_id):
                    self._emit("DEVICE_CONNECTED", device_id=device_id, status="REBOOTED")
        except KeyError:
            pass
        if not was_online:
            self._emit("DEVICE_CONNECTED", device_id=device_id, status="RECONNECTED")
        with self._lock:
            for mission in self._missions.values():
                if mission.device_id == device_id and mission.status == "RECONNECTING":
                    mission.reobserve_required = True

    def apply_transport_state(
        self,
        device_id: str,
        *,
        online: bool,
        trusted: bool = False,
        revoked: bool = False,
        model: str | None = None,
        manufacturer: str | None = None,
        serial_suffix: str | None = None,
        battery: int | None = None,
        current_app: str | None = None,
        boot_id: str | None = None,
        screen_state: str | None = None,
    ) -> None:
        """Apply one real transport observation. Does not invent trust or telemetry."""
        if not isinstance(device_id, str) or not device_id.startswith("dev_"):
            return
        try:
            self.registry.get(device_id)
            existed = True
        except KeyError:
            existed = False
        was_online = self._online.get(device_id) is True
        self.registry.upsert_discovered(
            device_id,
            model=model,
            manufacturer=manufacturer,
            serial_suffix=serial_suffix,
            online=online,
            battery=battery,
            current_app=current_app,
            boot_id=boot_id if online else None,
        )
        if screen_state and online:
            self.set_screen_state(device_id, screen_state)
        if revoked:
            if online:
                self._online[device_id] = True
            else:
                self.note_disconnect(device_id)
            if self.registry.get(device_id).trust != TrustState.REVOKED:
                self.revoke(device_id)
            return
        if not online:
            self.note_disconnect(device_id)
            return
        if not existed:
            self._online[device_id] = True
            self._emit("DEVICE_DISCOVERED", device_id=device_id)
        elif not was_online:
            self.note_reconnect(device_id, boot_id=boot_id)
        else:
            self._online[device_id] = True
            self.registry.mark_online(device_id)
        if trusted and self.registry.get(device_id).trust != TrustState.TRUSTED:
            self.trust(device_id)

    def stop_all(self) -> dict[str, Any]:
        stopped: list[str] = []
        with self._lock:
            for mission in self._missions.values():
                if mission.status in _TERMINAL:
                    continue
                mission.cancel.set()
                if mission.status != "RUNNING":
                    self._set_status(mission, "CANCELLED", failure_code="STOP_ALL", summary="Fleet stop.")
                else:
                    self._set_status(mission, "CANCELLING", failure_code="STOP_ALL", summary="Fleet stop.")
                stopped.append(mission.mission_id)
                self._emit("MISSION_CANCELLED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
            self._queues = {key: [] for key in self._queues}
        return {"stopped": stopped}

    def stop_device(self, device_id: str) -> dict[str, Any]:
        stopped: list[str] = []
        with self._lock:
            self._queues[device_id] = []
            for mission in self._missions.values():
                if mission.device_id != device_id or mission.status in _TERMINAL:
                    continue
                mission.cancel.set()
                if mission.status == "RUNNING":
                    self._set_status(mission, "CANCELLING", failure_code="STOP_DEVICE", summary="Device stop.")
                else:
                    self._set_status(mission, "CANCELLED", failure_code="STOP_DEVICE", summary="Device stop.")
                stopped.append(mission.mission_id)
        return {"deviceId": device_id, "stopped": stopped}

    def submit_command(self, text: str, *, priority: str = "NORMAL", confirmed: bool = False) -> dict[str, Any]:
        if confirmed and self._pending_confirmation is not None:
            plan = self._pending_confirmation
            self._pending_confirmation = None
            return self._launch(plan)
        plan = self.planner.plan(text, selected_device_id=self._selected_device_id, priority=priority)
        if plan.kind == "confirmation" and not confirmed:
            self._pending_confirmation = plan
            return {"kind": "confirmation", "message": plan.message, "candidates": plan.candidates, "goal": plan.goal}
        if plan.kind == "clarification":
            return {"kind": "clarification", "message": plan.message, "candidates": plan.candidates}
        if plan.kind == "answer":
            return {"kind": "answer", "message": plan.message}
        if plan.kind == "command":
            return self._run_command(plan)
        return self._launch(plan)

    def submit_plan(self, plan_body: dict[str, Any]) -> dict[str, Any]:
        missions_raw = plan_body.get("missions")
        if not isinstance(missions_raw, list) or not missions_raw:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A fleet plan needs at least one mission.")
        planned: list[PlanMission] = []
        for index, item in enumerate(missions_raw):
            if not isinstance(item, dict):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Mission entries must be objects.")
            target = item.get("target") if isinstance(item.get("target"), dict) else {}
            device_id = str(target.get("deviceId") or item.get("deviceId") or "")
            if not device_id.startswith("dev_"):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Each mission needs a stable deviceId.")
            try:
                record = self.registry.get(device_id)
            except KeyError as exc:
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "Device is not in the fleet registry.") from exc
            if record.trust != TrustState.TRUSTED:
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_REVOKED, f"{record.display_name} is not trusted.")
            session_id = str(target.get("sessionId") or item.get("sessionId") or "default-foreground")
            display_raw = target.get("displayId", item.get("displayId", 0))
            try:
                display_id = int(display_raw)
            except (TypeError, ValueError) as exc:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "displayId must be an integer.") from exc
            self._claim_session(device_id, session_id, display_id)
            objective = redact_objective(str(item.get("objective") or item.get("task") or ""))
            if not objective:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Mission objective is required.")
            depends = item.get("dependsOn") if isinstance(item.get("dependsOn"), list) else []
            planned.append(PlanMission(
                device_id=device_id,
                objective=objective,
                depends_on=[int(dep) for dep in depends],
                priority=str(item.get("priority") or "NORMAL"),
                handoff=item.get("handoff") if item.get("handoff") in {None, "OWNER_REQUIRED"} else None,
            ))
            # Bind a context now so a mismatched display fails before scheduling.
            bind_context(
                device_id=device_id,
                session_id=session_id,
                display_id=display_id,
                mission_id=f"pending-{index}",
                fleet_mission_id="pending",
            )
            if "sessionId" in item or "sessionId" in target:
                owner = self._named_sessions.get(session_id)
                if session_id != "default-foreground" and owner not in (None, device_id):
                    raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH, "REJECTED: session belongs to another device")
        plan = FleetPlan("missions", redact_objective(str(plan_body.get("goal") or "Fleet plan")), missions=planned)
        # Preserve explicit session bindings via a side channel on the plan missions.
        self._explicit_sessions = getattr(self, "_explicit_sessions", {})
        return self._launch(plan, explicit=missions_raw)

    def missions(self) -> list[dict[str, Any]]:
        with self._lock:
            return [self._fleet_public(item) for item in self._fleet.values()]

    def mission(self, mission_id: str) -> dict[str, Any]:
        with self._lock:
            if mission_id in self._fleet:
                return self._fleet_public(self._fleet[mission_id])
            child = self._missions.get(mission_id)
            if child is None:
                raise KeyError(mission_id)
            return child.public()

    def cancel(self, mission_id: str) -> dict[str, Any]:
        with self._lock:
            if mission_id in self._fleet:
                for child_id in self._fleet[mission_id].child_ids:
                    self._cancel_locked(child_id)
                return self._fleet_public(self._fleet[mission_id])
            self._cancel_locked(mission_id)
            return self._missions[mission_id].public()

    def pause(self, mission_id: str) -> dict[str, Any]:
        with self._lock:
            targets = self._children_of(mission_id)
            for mission in targets:
                if mission.status in _TERMINAL:
                    continue
                mission.cancel.set()
                self._set_status(mission, "PAUSED", failure_code="PAUSED", summary="Paused.")
                self._emit("MISSION_PAUSED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
            return self.mission(mission_id) if mission_id in self._fleet or mission_id in self._missions else {}

    def resume(self, mission_id: str) -> dict[str, Any]:
        with self._lock:
            targets = self._children_of(mission_id)
            for mission in targets:
                if mission.status not in {"PAUSED", "WAITING_OWNER", "RECONNECTING"}:
                    continue
                mission.reobserve_required = True
                mission.cancel = threading.Event()
                self._set_status(mission, "QUEUED", failure_code=None)
                self._queues.setdefault(mission.device_id, []).append(mission.mission_id)
        self._pump()
        return self.mission(mission_id)

    def events(self, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._events[-limit:])

    def snapshot(self) -> dict[str, Any]:
        devices = []
        for record in self.registry.list():
            current = self._current_mission(record.device_id)
            devices.append({
                **record.public(),
                "inputOwner": self._control.get(record.device_id, "AGENT"),
                "currentMission": current.public() if current else None,
            })
        online = sum(1 for device in devices if device.get("online"))
        running = sum(1 for mission in self._missions.values() if mission.status == "RUNNING")
        waiting = sum(1 for mission in self._missions.values() if mission.status == "WAITING_OWNER")
        return scrub({
            "controllerDeviceId": self.registry.controller_id,
            "devices": devices,
            "counts": {
                "devices": len(devices),
                "online": online,
                "running": running,
                "waitingForOwner": waiting,
                "offline": len(devices) - online,
            },
            "missions": self.missions(),
            "peakConcurrency": self._peak_concurrency,
        })

    def dispatch_guard(self, mission_id: str, *, device_id: str, session_id: str | None = None, display_id: int | None = None) -> None:
        """Used by callers and tests to prove a mission cannot run on the wrong device."""
        with self._lock:
            mission = self._missions.get(mission_id)
        if mission is None:
            raise KeyError(mission_id)
        assert_context(mission.context(), device_id=device_id, session_id=session_id, display_id=display_id)
        if session_id and session_id != "default-foreground":
            owner = self._named_sessions.get(session_id)
            if owner and owner != device_id:
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH, "REJECTED: session belongs to another device")

    def wait_idle(self, timeout: float = 3.0) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self._lock:
                if not any(mission.status in _ACTIVE | {"CANCELLING"} for mission in self._missions.values()):
                    return True
            time.sleep(0.01)
        return False

    def _launch(self, plan: FleetPlan, explicit: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        if not plan.missions:
            return {"kind": "clarification", "message": plan.message or "Nothing to run."}
        fleet_id = f"flt_{secrets.token_hex(8)}"
        child_ids: list[str] = []
        index_to_id: dict[int, str] = {}
        created: list[DeviceMission] = []
        for index, item in enumerate(plan.missions):
            record = self.registry.get(item.device_id)
            if record.trust != TrustState.TRUSTED:
                raise DesktopRuntimeError(RuntimeErrorCode.TRUST_REVOKED, f"{record.display_name} is not trusted.")
            session_id, display_id = self._session_for_device(item.device_id)
            if explicit and index < len(explicit):
                target = explicit[index].get("target") if isinstance(explicit[index].get("target"), dict) else explicit[index]
                if target.get("sessionId"):
                    session_id = str(target["sessionId"])
                if "displayId" in target and target.get("displayId") is not None:
                    display_id = int(target["displayId"])
            self._claim_session(item.device_id, session_id, display_id)
            mission_id = f"msn_{secrets.token_hex(8)}"
            index_to_id[index] = mission_id
            mission = DeviceMission(
                mission_id=mission_id,
                fleet_mission_id=fleet_id,
                device_id=item.device_id,
                session_id=session_id,
                display_id=display_id,
                objective=item.objective,
                priority=item.priority if item.priority in _PRIORITIES else "NORMAL",
                handoff=item.handoff,
                capabilities=list(item.capabilities),
            )
            created.append(mission)
            child_ids.append(mission_id)
        for index, mission in enumerate(created):
            mission.depends_on = [index_to_id[dep] for dep in plan.missions[index].depends_on if dep in index_to_id]
        fleet = FleetMission(fleet_id, plan.goal, child_ids, message=plan.message)
        with self._lock:
            self._fleet[fleet_id] = fleet
            for mission in created:
                self._missions[mission.mission_id] = mission
                self._online.setdefault(mission.device_id, self.registry.get(mission.device_id).online)
                self._emit("MISSION_CREATED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=fleet_id)
                if self._dependencies_ready(mission):
                    self._queues.setdefault(mission.device_id, []).append(mission.mission_id)
                else:
                    self._set_status(mission, "QUEUED", summary="Waiting on another device.")
        self._pump()
        with self._lock:
            return self._fleet_public(fleet)

    def _run_command(self, plan: FleetPlan) -> dict[str, Any]:
        if plan.command == "status":
            return {"kind": "status", "snapshot": self.snapshot()}
        if plan.command == "stop-all":
            return {"kind": "command", "result": self.stop_all()}
        if plan.command == "stop-device" and plan.command_target:
            return {"kind": "command", "result": self.stop_device(plan.command_target)}
        if plan.command == "take-control" and plan.command_target:
            return {"kind": "command", "result": self.take_control(plan.command_target)}
        if plan.command == "continue" and plan.command_target:
            return {"kind": "command", "result": self.release_control(plan.command_target)}
        if plan.command == "rename" and plan.command_target:
            return {"kind": "command", "result": self.rename(plan.command_target, plan.message)}
        return {"kind": "clarification", "message": plan.message or "I couldn't map that command."}

    def _pump(self) -> None:
        scheduled: list[str] = []
        with self._lock:
            if not self._alive:
                return
            for device_id, queue in list(self._queues.items()):
                if self._inflight.get(device_id):
                    continue
                ready: list[tuple[int, int, str]] = []
                for index, mission_id in enumerate(queue):
                    mission = self._missions.get(mission_id)
                    if mission and mission.status == "QUEUED" and self._dependencies_ready(mission):
                        ready.append((_PRIORITIES.get(mission.priority, 2), index, mission_id))
                if not ready:
                    continue
                ready.sort()
                _priority, ready_index, mission_id = ready[0]
                queue.pop(ready_index)
                self._inflight[device_id] = mission_id
                scheduled.append(mission_id)
        for mission_id in scheduled:
            self._executor.submit(self._worker, mission_id)

    def _worker(self, mission_id: str) -> None:
        with self._concurrency_lock:
            self._concurrency += 1
            self._peak_concurrency = max(self._peak_concurrency, self._concurrency)
        try:
            with self._lock:
                mission = self._missions.get(mission_id)
                device_id = mission.device_id if mission else ""
            if mission is None:
                return
            device_lock = self._device_lock(device_id)
            with device_lock:
                self._execute(mission)
        finally:
            with self._concurrency_lock:
                self._concurrency -= 1
            with self._lock:
                if mission is not None:
                    self._inflight[mission.device_id] = None
                    self._refresh_fleet(mission.fleet_mission_id)
            self._pump()

    def _execute(self, mission: DeviceMission) -> None:
        with self._lock:
            if mission.cancel.is_set() or mission.status in {"CANCELLED", "STOPPED", "CANCELLING"}:
                self._set_status(mission, "CANCELLED" if mission.failure_code != "REVOKED" else "STOPPED", failure_code=mission.failure_code or "CANCELLED")
                return
            self._set_status(mission, "RUNNING")
            self._emit("MISSION_STARTED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
        try:
            record = self.registry.get(mission.device_id)
        except KeyError:
            self._finish(mission, "FAILED", "DEVICE_NOT_FOUND", "Device disappeared from the registry.", verified=False)
            return
        if record.trust != TrustState.TRUSTED:
            self._finish(mission, "STOPPED", "REVOKED", "Device is not trusted.", verified=False)
            self._emit("MISSION_CANCELLED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
            return
        if self._control.get(mission.device_id) == "HUMAN":
            self._finish(mission, "PAUSED", "HUMAN_CONTROL", "Owner has this device.", verified=False)
            return
        if not self._online.get(mission.device_id, record.online):
            mission.reobserve_required = True
            self._finish(mission, "RECONNECTING", "DISCONNECTED", "Device is offline. It will re-observe before continuing.", verified=False)
            return
        screen = self.power.ensure_awake(mission.device_id)
        if screen == ScreenState.OWNER_REQUIRED:
            self._finish(mission, "WAITING_OWNER", "OWNER_REQUIRED", self.power.owner_requests[-1]["message"] if self.power.owner_requests else "Unlock required.", verified=False)
            self._emit("MISSION_WAITING_OWNER", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
            self._emit("DEVICE_LOCKED", device_id=mission.device_id, mission_id=mission.mission_id)
            return
        if self.runner is None:
            self._finish(mission, "FAILED", "CAPABILITY_UNAVAILABLE", "No phone runner is configured.", verified=False)
            return
        if re.search(r"(?i)\b(adb\s+shell|su\s+-c|/system/bin/sh)\b", mission.objective):
            self._finish(mission, "FAILED", "POLICY_DENIED", "Shell is not a fleet capability.", verified=False)
            return
        action_id = f"{mission.mission_id}:step:{mission.step}"
        ctx = mission.context(step_id=str(mission.step), action_id=action_id)
        assert_context(ctx, device_id=mission.device_id, session_id=mission.session_id, display_id=mission.display_id)
        hooks = ExecutionHooks(
            cancelled=mission.cancel.is_set,
            trusted=lambda: self._is_trusted(mission.device_id),
            online=lambda: self._online.get(mission.device_id, False),
            human=lambda: self._control.get(mission.device_id) == "HUMAN",
            reobserve_required=lambda: False,
        )
        unverified_side_effect = bool(mission.applied_action_ids) and not mission.verified and mission.failure_code in {
            "NO_BLIND_REPLAY", "REOBSERVE_AFTER_RESTART", "DISCONNECTED", None,
        }
        if mission.reobserve_required or (action_id in mission.applied_action_ids and not mission.verified):
            observation = scrub(self.runner.observe(ctx))
            mission.last_observation = observation if isinstance(observation, dict) else {}
            mission.reobserve_required = False
            self._emit(
                "ACTION_COMPLETED",
                device_id=mission.device_id,
                mission_id=mission.mission_id,
                fleet_mission_id=mission.fleet_mission_id,
                status="REOBSERVE",
            )
            if unverified_side_effect or action_id in mission.applied_action_ids:
                self._finish(
                    mission,
                    "PAUSED",
                    "REOBSERVE_AFTER_RECONNECT",
                    "Re-observed the device. The previous action was not replayed.",
                    verified=False,
                )
                return
        if action_id not in mission.applied_action_ids:
            mission.applied_action_ids.append(action_id)
            mission.verified = False
            with self._lock:
                self._persist_journal()
        self._emit("ACTION_STARTED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id, action_id=action_id)
        try:
            result = self.runner.run(ctx, mission.objective, hooks)
        except DesktopRuntimeError as exc:
            if exc.code in {RuntimeErrorCode.PHONE_LOCKED.value, "PHONE_LOCKED"}:
                self._finish(mission, "WAITING_OWNER", "OWNER_REQUIRED", exc.safe_message, verified=False)
                self._emit("MISSION_WAITING_OWNER", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
                self._emit("DEVICE_LOCKED", device_id=mission.device_id, mission_id=mission.mission_id)
                return
            self._finish(mission, "FAILED", exc.code, exc.safe_message, verified=False)
            return
        except Exception as exc:  # one device must not kill the fleet
            self._finish(mission, "FAILED", exc.__class__.__name__, str(exc)[:240], verified=False)
            return
        if not isinstance(result, GoalResult):
            self._finish(mission, "FAILED", "PROTOCOL_MISMATCH", "Runner returned an unexpected result.", verified=False)
            return
        for seen in result.action_ids:
            if seen not in mission.applied_action_ids:
                mission.applied_action_ids.append(seen)
        mission.step += 1
        mission.last_observation = scrub(result.observation) if isinstance(result.observation, dict) else {}
        if result.status == "revoked" or mission.failure_code == "REVOKED":
            self._finish(mission, "STOPPED", "REVOKED", result.summary, verified=False)
            return
        if mission.cancel.is_set() or result.status == "cancelled":
            self._finish(mission, "CANCELLED", mission.failure_code or "CANCELLED", result.summary, verified=False)
            return
        if result.status == "disconnected":
            mission.reobserve_required = True
            self._finish(mission, "RECONNECTING", "NO_BLIND_REPLAY", result.summary, verified=False)
            return
        if result.status == "revoked":
            self._finish(mission, "STOPPED", "REVOKED", result.summary, verified=False)
            return
        if result.status in {"paused", "waiting_owner"}:
            code = "OWNER_REQUIRED" if result.status == "waiting_owner" else "HUMAN_CONTROL"
            status = "WAITING_OWNER" if result.status == "waiting_owner" else "PAUSED"
            self._finish(mission, status, code, result.summary, verified=False)
            return
        if result.status == "reobserved":
            self._finish(mission, "PAUSED", "REOBSERVE_AFTER_RECONNECT", result.summary, verified=False)
            return
        if result.verified and result.status == "completed":
            self._finish(mission, "COMPLETED", None, result.summary, verified=True)
            self._emit("MISSION_COMPLETED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
            self._release_dependents(mission.mission_id)
            return
        self._finish(mission, "FAILED", "UNVERIFIED", result.summary or "The phone did not verify the goal.", verified=False)
        self._emit("MISSION_FAILED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
        self._cancel_dependents(mission.mission_id)

    def _finish(self, mission: DeviceMission, status: str, code: str | None, summary: str | None, *, verified: bool) -> None:
        with self._lock:
            mission.verified = verified
            self._set_status(mission, status, failure_code=code, summary=summary)
            self._refresh_fleet(mission.fleet_mission_id)

    def _release_dependents(self, mission_id: str) -> None:
        with self._lock:
            for mission in self._missions.values():
                if mission_id in mission.depends_on and mission.status == "QUEUED" and self._dependencies_ready(mission):
                    if mission.mission_id not in self._queues.get(mission.device_id, []):
                        self._queues.setdefault(mission.device_id, []).append(mission.mission_id)
        self._pump()

    def _cancel_dependents(self, mission_id: str) -> None:
        with self._lock:
            for mission in self._missions.values():
                if mission_id in mission.depends_on and mission.status not in _TERMINAL:
                    self._set_status(mission, "CANCELLED", failure_code="DEPENDENCY_FAILED", summary="An earlier device mission failed.")

    def _dependencies_ready(self, mission: DeviceMission) -> bool:
        for dependency in mission.depends_on:
            other = self._missions.get(dependency)
            if other is None or other.status != "COMPLETED":
                return False
        return True

    def _cancel_locked(self, mission_id: str) -> None:
        mission = self._missions.get(mission_id)
        if mission is None:
            raise KeyError(mission_id)
        mission.cancel.set()
        if mission.status not in _TERMINAL and mission.status != "RUNNING":
            self._set_status(mission, "CANCELLED", failure_code="CANCELLED", summary="Cancelled.")
        elif mission.status == "RUNNING":
            self._set_status(mission, "CANCELLING", failure_code="CANCELLED", summary="Cancelling.")
        self._queues[mission.device_id] = [item for item in self._queues.get(mission.device_id, []) if item != mission_id]
        self._emit("MISSION_CANCELLED", device_id=mission.device_id, mission_id=mission.mission_id, fleet_mission_id=mission.fleet_mission_id)
        self._refresh_fleet(mission.fleet_mission_id)

    def _children_of(self, mission_id: str) -> list[DeviceMission]:
        if mission_id in self._fleet:
            return [self._missions[child_id] for child_id in self._fleet[mission_id].child_ids if child_id in self._missions]
        mission = self._missions.get(mission_id)
        if mission is None:
            raise KeyError(mission_id)
        return [mission]

    def _set_status(self, mission: DeviceMission, status: str, *, failure_code: str | None = None, summary: str | None = None) -> None:
        mission.status = status
        mission.updated_at_ms = now_ms()
        if failure_code is not None or status in _TERMINAL | {"PAUSED", "WAITING_OWNER", "RECONNECTING"}:
            mission.failure_code = failure_code
        if summary is not None:
            mission.result_summary = redact_objective(summary)[:500]
        self._persist_journal()

    def _refresh_fleet(self, fleet_id: str) -> None:
        fleet = self._fleet.get(fleet_id)
        if fleet is None:
            return
        children = [self._missions[child_id] for child_id in fleet.child_ids if child_id in self._missions]
        fleet.status = _aggregate(children)
        fleet.updated_at_ms = now_ms()
        self._persist_journal()

    def _fleet_public(self, fleet: FleetMission) -> dict[str, Any]:
        children = [self._missions[child_id] for child_id in fleet.child_ids if child_id in self._missions]
        return fleet.public(children)

    def _current_mission(self, device_id: str) -> DeviceMission | None:
        for mission in self._missions.values():
            if mission.device_id == device_id and mission.status in {"RUNNING", "WAITING_OWNER", "PAUSED", "RECONNECTING", "QUEUED"}:
                return mission
        return None

    def _emit(self, event_type: str, *, device_id: str, mission_id: str | None = None, fleet_mission_id: str | None = None, **extra: Any) -> None:
        event = scrub({
            "type": event_type,
            "deviceId": device_id,
            "missionId": mission_id,
            "fleetMissionId": fleet_mission_id,
            "timestamp": now_ms(),
            **extra,
        })
        self._events.append(event)
        if len(self._events) > 400:
            del self._events[:100]

    def _device_lock(self, device_id: str) -> threading.Lock:
        with self._lock:
            lock = self._device_locks.get(device_id)
            if lock is None:
                lock = threading.Lock()
                self._device_locks[device_id] = lock
            return lock

    def _is_trusted(self, device_id: str) -> bool:
        try:
            return self.registry.get(device_id).trust == TrustState.TRUSTED
        except KeyError:
            return False

    def _claim_session(self, device_id: str, session_id: str, display_id: int) -> None:
        bind_context(
            device_id=device_id,
            session_id=session_id,
            display_id=display_id,
            mission_id="bind",
            fleet_mission_id="bind",
        )
        if session_id == "default-foreground":
            if display_id != 0:
                raise DesktopRuntimeError(RuntimeErrorCode.SESSION_DISPLAY_MISMATCH, "REJECTED: foreground display must be 0")
            return
        with self._lock:
            owner = self._named_sessions.get(session_id)
            if owner and owner != device_id:
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH, "REJECTED: session belongs to another device")
            self._named_sessions[session_id] = device_id

    def _record_or_none(self, device_id: str):
        try:
            return self.registry.get(device_id)
        except KeyError:
            return None

    def _default_probe(self, device_id: str) -> ScreenState:
        try:
            record = self.registry.get(device_id)
        except KeyError:
            return ScreenState.UNKNOWN
        raw = record.screen_state or "UNKNOWN"
        try:
            return ScreenState(raw)
        except ValueError:
            return ScreenState.UNKNOWN

    def set_screen_state(self, device_id: str, state: str) -> None:
        record = self.registry.get(device_id)
        record.screen_state = state

    def _persist_journal(self) -> None:
        if self.journal_path is None:
            return
        payload = scrub({
            "schemaVersion": 1,
            "missions": [mission.public() for mission in self._missions.values()],
            "fleet": [
                {
                    "fleetMissionId": fleet.fleet_mission_id,
                    "goal": fleet.goal,
                    "status": fleet.status,
                    "childIds": fleet.child_ids,
                    "message": fleet.message,
                    "createdAt": fleet.created_at_ms,
                    "updatedAt": fleet.updated_at_ms,
                }
                for fleet in self._fleet.values()
            ],
        })
        self.journal_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.journal_path.with_name(f"{self.journal_path.name}.{threading.get_ident()}.tmp")
        temporary.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
        os.replace(temporary, self.journal_path)

    def _load_journal(self) -> None:
        if self.journal_path is None or not self.journal_path.is_file():
            return
        try:
            payload = json.loads(self.journal_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        for item in payload.get("fleet") or []:
            if not isinstance(item, dict):
                continue
            fleet = FleetMission(
                fleet_mission_id=str(item.get("fleetMissionId")),
                goal=str(item.get("goal") or ""),
                child_ids=list(item.get("childIds") or []),
                status=str(item.get("status") or "PAUSED"),
                message=str(item.get("message") or ""),
            )
            self._fleet[fleet.fleet_mission_id] = fleet
        for item in payload.get("missions") or []:
            if not isinstance(item, dict):
                continue
            status = str(item.get("status") or "PAUSED")
            reobserve = bool(item.get("reobserveRequired"))
            failure = item.get("failureCode")
            if status in {"RUNNING", "CANCELLING", "QUEUED"}:
                status = "PAUSED"
                reobserve = True
                failure = "REOBSERVE_AFTER_RESTART"
            mission = DeviceMission(
                mission_id=str(item["missionId"]),
                fleet_mission_id=str(item.get("fleetMissionId") or ""),
                device_id=str(item.get("deviceId") or ""),
                session_id=str(item.get("sessionId") or "default-foreground"),
                display_id=int(item.get("displayId") or 0),
                objective=str(item.get("objective") or ""),
                priority=str(item.get("priority") or "NORMAL"),
                depends_on=list(item.get("dependsOn") or []),
                status=status,
                step=int(item.get("step") or 0),
                result_summary=item.get("result"),
                failure_code=failure,
                reobserve_required=reobserve,
                verified=bool(item.get("verified")),
                applied_action_ids=list(item.get("appliedActionIds") or []),
                handoff=item.get("handoff"),
            )
            self._missions[mission.mission_id] = mission
        # Do not auto-schedule recovered work.


def _aggregate(children: list[DeviceMission]) -> str:
    if not children:
        return "COMPLETED"
    statuses = [child.status for child in children]
    if any(status in {"RUNNING", "CANCELLING"} for status in statuses):
        return "RUNNING"
    if any(status == "QUEUED" for status in statuses):
        return "QUEUED"
    if any(status == "WAITING_OWNER" for status in statuses):
        if any(status in {"FAILED", "STOPPED"} for status in statuses) and any(status == "COMPLETED" for status in statuses):
            return "PARTIAL_FAILURE"
        return "WAITING_OWNER"
    if any(status == "RECONNECTING" for status in statuses):
        return "RECONNECTING"
    if any(status == "PAUSED" for status in statuses):
        return "PAUSED"
    if all(status == "COMPLETED" for status in statuses):
        return "COMPLETED"
    if all(status == "CANCELLED" for status in statuses):
        return "CANCELLED"
    if any(status == "COMPLETED" for status in statuses) and any(status in {"FAILED", "STOPPED", "CANCELLED"} for status in statuses):
        return "PARTIAL_FAILURE"
    if any(status == "STOPPED" for status in statuses):
        return "STOPPED"
    return "FAILED"
