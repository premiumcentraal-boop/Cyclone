"""Fleet planner. Deterministic first; an optional model may propose a plan that is validated here.

The model never receives API keys, and a proposal is not executed until it validates.
Device agents still decide how to act on a single phone.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import re
from typing import Any, Callable, Protocol

from .redact import looks_like_secret_handoff, redact_objective, scrub
from .registry import DeviceRecord, DeviceRegistry
from .resolver import DeviceNameResolver

_ON_SPLIT = re.compile(r"(?i)(?=(?:^|[.;]\s*)\bon\s+)")
_ON_CLAUSE = re.compile(r"(?i)^on\s+([^,]{1,48}),\s*(.+)$", re.DOTALL)
_TASK_ON = re.compile(r"(?i)^(.+?)\s+on\s+([^,.;]{1,48})$")
_BROADCAST = re.compile(
    r"(?i)^(?:run|do)\s+(.+?)\s+on\s+(?:all|every)\s+(.+?)(?:\s+devices)?\.?$"
)
_RENAME = re.compile(r"(?i)^rename\s+(?:this\s+phone|(.+?))\s+to\s+(.+)$")
_STOP_DEVICE = re.compile(r"(?i)^stop(?:\s+the\s+task)?(?:\s+on)?\s+(.+)$")
_TAKEOVER = re.compile(r"(?i)^take\s+over\s+(.+)$")
_CONTINUE = re.compile(r"(?i)^continue\s+(.+)$")

_CONSEQUENTIAL = ("send", "delete", "pay", "purchase", "buy", "post ", "submit", "remove the", "wipe")
_APP_HINTS = {
    "gmail": "launchApp",
    "whatsapp": "launchApp",
    "youtube": "launchApp",
    "chrome": "launchApp",
    "camera": "camera",
    "photo": "camera",
}


class ModelPlanner(Protocol):
    def propose(self, snapshot: dict[str, Any], text: str) -> dict[str, Any] | None: ...


@dataclass
class PlanMission:
    device_id: str
    objective: str
    depends_on: list[int] = field(default_factory=list)
    priority: str = "NORMAL"
    handoff: str | None = None
    capabilities: list[str] = field(default_factory=list)


@dataclass
class FleetPlan:
    kind: str  # missions, answer, clarification, confirmation, command
    goal: str
    missions: list[PlanMission] = field(default_factory=list)
    message: str = ""
    command: str | None = None
    command_target: str | None = None
    candidates: list[str] = field(default_factory=list)
    consequential: bool = False

    def public(self) -> dict[str, Any]:
        return scrub({
            "kind": self.kind,
            "goal": self.goal,
            "message": self.message,
            "command": self.command,
            "commandTarget": self.command_target,
            "candidates": self.candidates,
            "consequential": self.consequential,
            "missions": [
                {
                    "deviceId": item.device_id,
                    "objective": item.objective,
                    "dependsOn": item.depends_on,
                    "priority": item.priority,
                    "handoff": item.handoff,
                    "capabilities": item.capabilities,
                }
                for item in self.missions
            ],
        })


class FleetPlanner:
    def __init__(
        self,
        registry: DeviceRegistry,
        resolver: DeviceNameResolver | None = None,
        model: ModelPlanner | None = None,
    ):
        self.registry = registry
        self.resolver = resolver or DeviceNameResolver()
        self.model = model

    def plan(self, text: str, *, selected_device_id: str | None = None, priority: str = "NORMAL") -> FleetPlan:
        raw = " ".join(str(text or "").strip().split())
        if not raw:
            return FleetPlan("clarification", "", message="Say what you want done, and on which device if it matters.")
        if self.model is not None:
            snapshot = self.planner_snapshot()
            proposed = self.model.propose(snapshot, redact_objective(raw))
            if isinstance(proposed, dict) and proposed.get("missions"):
                validated = self._validate_model_plan(proposed, raw, priority)
                if validated is not None:
                    return validated
        return self._deterministic(raw, selected_device_id=selected_device_id, priority=priority)

    def planner_snapshot(self) -> dict[str, Any]:
        devices = []
        for record in self.registry.list():
            devices.append({
                "id": record.device_id,
                "name": record.display_name,
                "role": record.role,
                "state": "READY" if record.online and record.trust.value == "TRUSTED" else record.trust.value,
                "trusted": record.trust.value == "TRUSTED",
                "online": record.online,
                "currentApp": record.current_app,
                "battery": record.battery,
                "locked": record.screen_state in {"LOCKED", "OWNER_REQUIRED"},
                "capabilities": [key for key, enabled in record.capabilities.items() if enabled and key != "shell"],
                "groups": list(record.group_ids),
            })
        return scrub({
            "controllerDeviceId": self.registry.controller_id,
            "devices": devices,
        })

    def _deterministic(self, raw: str, *, selected_device_id: str | None, priority: str) -> FleetPlan:
        folded = raw.casefold().rstrip(".")
        if folded in {"show my devices", "which devices are online", "what is running on each device", "what's running"}:
            return FleetPlan("command", raw, command="status", message="Fleet status.")
        if folded in {"stop everything", "stop all", "stop all devices"}:
            return FleetPlan("command", raw, command="stop-all", message="Stop every running mission.")
        rename = _RENAME.match(raw.strip())
        if rename:
            target = (rename.group(1) or "").strip() or None
            if target is None:
                target_id = selected_device_id
                if not target_id:
                    return FleetPlan("clarification", raw, message="Which phone should I rename?")
            else:
                resolved = self._resolve_phrase(target)
                if resolved.kind != "one":
                    return resolved.plan
                target_id = resolved.device_id
            return FleetPlan("command", raw, command="rename", command_target=target_id, message=rename.group(2).strip())
        takeover = _TAKEOVER.match(raw.strip())
        if takeover:
            resolved = self._resolve_phrase(takeover.group(1))
            if resolved.kind != "one":
                return resolved.plan
            return FleetPlan("command", raw, command="take-control", command_target=resolved.device_id)
        cont = _CONTINUE.match(raw.strip())
        if cont:
            resolved = self._resolve_phrase(cont.group(1))
            if resolved.kind != "one":
                return resolved.plan
            return FleetPlan("command", raw, command="continue", command_target=resolved.device_id)
        if folded.startswith("stop"):
            match = _STOP_DEVICE.match(raw.strip())
            if match and folded not in {"stop"}:
                phrase = match.group(1).strip()
                if phrase.casefold() in {"everything", "all", "all devices"}:
                    return FleetPlan("command", raw, command="stop-all")
                resolved = self._resolve_phrase(phrase)
                if resolved.kind != "one":
                    return resolved.plan
                return FleetPlan("command", raw, command="stop-device", command_target=resolved.device_id)

        broadcast = _BROADCAST.match(raw.strip())
        if broadcast:
            return self._broadcast(raw, broadcast.group(1).strip(), broadcast.group(2).strip(), priority)

        clauses = self._clauses(raw)
        if len(clauses) >= 2 or (len(clauses) == 1 and clauses[0][0]):
            return self._from_clauses(raw, clauses, priority)

        if looks_like_secret_handoff(raw) and " on " in raw.casefold():
            return self._secret_handoff(raw, priority)

        resolved_inline = _TASK_ON.match(raw.strip())
        if resolved_inline:
            return self._from_clauses(raw, [(resolved_inline.group(2).strip(), resolved_inline.group(1).strip())], priority)

        return self._route_unspecified(raw, priority)

    def _clauses(self, raw: str) -> list[tuple[str, str]]:
        parts = [part.strip(" .;") for part in _ON_SPLIT.split(raw) if part.strip(" .;")]
        found: list[tuple[str, str]] = []
        for part in parts:
            match = _ON_CLAUSE.match(part.strip())
            if not match:
                continue
            device = match.group(1).strip()
            task = match.group(2).strip().rstrip(".")
            found.append((device, task))
        if found:
            return found
        pieces = re.split(r"(?i)\s+and\s+", raw)
        if len(pieces) > 1:
            paired: list[tuple[str, str]] = []
            for piece in pieces:
                match = _TASK_ON.match(piece.strip().rstrip("."))
                if not match:
                    return []
                paired.append((match.group(2).strip(), match.group(1).strip()))
            return paired
        return []

    def _from_clauses(self, raw: str, clauses: list[tuple[str, str]], priority: str) -> FleetPlan:
        missions: list[PlanMission] = []
        for device_phrase, task in clauses:
            resolved = self._resolve_phrase(device_phrase)
            if resolved.kind != "one":
                return resolved.plan
            objective = redact_objective(task)
            record = self.registry.get(resolved.device_id)
            capability_issue = self._capability_issue(record, objective)
            if capability_issue is not None:
                return capability_issue
            missions.append(PlanMission(
                device_id=resolved.device_id,
                objective=objective,
                priority=priority,
                capabilities=sorted(required_capabilities(objective)),
            ))
        if looks_like_secret_handoff(raw) and len(missions) >= 2:
            missions[1].depends_on = [0]
            missions[0].handoff = "OWNER_REQUIRED"
            missions[1].handoff = "OWNER_REQUIRED"
            missions[0].objective = redact_objective(
                "Find the requested verification on this device and stop for the owner. Do not copy the secret into the fleet."
            )
            missions[1].objective = "Continue only after the owner approves the handoff. Do not expect the secret in fleet state."
            return FleetPlan("missions", "Owner-approved handoff between devices.", missions=missions)
        return FleetPlan("missions", redact_objective(raw), missions=missions)

    def _secret_handoff(self, raw: str, priority: str) -> FleetPlan:
        devices = re.findall(r"(?i)\bon\s+(?:my\s+)?([^,.]+)", raw)
        if len(devices) < 2:
            return FleetPlan(
                "clarification",
                raw,
                message="Which device has the secret, and which device should continue? I will not copy the secret between them.",
            )
        return self._from_clauses(raw, [(devices[0].strip(), "find"), (devices[1].strip(), "use")], priority)

    def _broadcast(self, raw: str, task: str, group_phrase: str, priority: str) -> FleetPlan:
        objective = redact_objective(task)
        consequential = any(word in objective.casefold() for word in _CONSEQUENTIAL)
        phrase = group_phrase.casefold().strip()
        if phrase.startswith("online"):
            targets = [record for record in self.registry.list() if record.online and record.trust.value == "TRUSTED"]
        else:
            targets = [
                record for record in self.registry.group_members(phrase)
                if record.trust.value == "TRUSTED"
            ]
        if not targets:
            return FleetPlan("clarification", raw, message=f"I couldn't find trusted devices for “{group_phrase}”.")
        if consequential:
            return FleetPlan(
                "confirmation",
                redact_objective(raw),
                missions=[
                    PlanMission(record.device_id, objective, priority=priority, capabilities=sorted(required_capabilities(objective)))
                    for record in targets
                ],
                message=f"This affects {len(targets)} devices. Confirm before I run it.",
                consequential=True,
                candidates=[record.display_name for record in targets],
            )
        return FleetPlan(
            "missions",
            redact_objective(raw),
            missions=[
                PlanMission(record.device_id, objective, priority=priority, capabilities=sorted(required_capabilities(objective)))
                for record in targets
            ],
        )

    def _route_unspecified(self, raw: str, priority: str) -> FleetPlan:
        trusted = [record for record in self.registry.list() if record.trust.value == "TRUSTED" and record.online]
        if not trusted:
            return FleetPlan("clarification", raw, message="No trusted device is online.")
        needed = required_capabilities(raw)
        capable = [record for record in trusted if needed.issubset({key for key, on in record.capabilities.items() if on})]
        preferred = [record for record in capable if record.preferred or (record.role == "WORK" and "work" in raw.casefold())]
        pool = preferred or capable
        if len(pool) == 1:
            record = pool[0]
            return FleetPlan(
                "missions",
                redact_objective(raw),
                missions=[PlanMission(record.device_id, redact_objective(raw), priority=priority, capabilities=sorted(needed))],
                message=f"Selected {record.display_name} because it is online, trusted, and can do this.",
            )
        if len(pool) > 1:
            names = [record.display_name for record in pool]
            return FleetPlan(
                "clarification",
                raw,
                message="Which device should I use: " + " or ".join(names) + "?",
                candidates=names,
            )
        return FleetPlan("clarification", raw, message="None of the trusted devices can do that.")

    def _capability_issue(self, record: DeviceRecord, objective: str) -> FleetPlan | None:
        needed = required_capabilities(objective)
        missing = [item for item in needed if not record.capabilities.get(item, False)]
        if not missing:
            restricted = [app for app in record.restricted_apps if app.casefold() in objective.casefold()]
            if restricted:
                return FleetPlan(
                    "clarification",
                    objective,
                    message=f"{record.display_name} is not allowed to run {restricted[0]}.",
                )
            return None
        alternatives = [
            other.display_name for other in self.registry.list()
            if other.device_id != record.device_id
            and other.trust.value == "TRUSTED"
            and other.online
            and all(other.capabilities.get(item, False) for item in needed)
        ]
        hint = f" {alternatives[0]} can." if alternatives else ""
        return FleetPlan(
            "clarification",
            objective,
            message=f"{record.display_name} can't do that ({', '.join(missing)}).{hint}",
            candidates=alternatives,
        )

    def _resolve_phrase(self, phrase: str) -> _Resolved:
        result = self.resolver.resolve(phrase, self.registry.list())
        if result.unique and result.device is not None:
            if result.device.trust.value == "REVOKED":
                return _Resolved("plan", plan=FleetPlan(
                    "clarification",
                    phrase,
                    message=f"{result.device.display_name} is revoked and cannot run tasks.",
                ))
            return _Resolved("one", device_id=result.device.device_id)
        if result.status == "AMBIGUOUS":
            names = [device.display_name for device in result.matches]
            return _Resolved("plan", plan=FleetPlan(
                "clarification",
                phrase,
                message="Which device should I use: " + " or ".join(names) + "?",
                candidates=names,
            ))
        return _Resolved("plan", plan=FleetPlan(
            "clarification",
            phrase,
            message=f"I don't know which device “{phrase.strip()}” is.",
        ))

    def _validate_model_plan(self, proposed: dict[str, Any], raw: str, priority: str) -> FleetPlan | None:
        missions_raw = proposed.get("missions")
        if not isinstance(missions_raw, list) or not missions_raw:
            return None
        missions: list[PlanMission] = []
        for item in missions_raw:
            if not isinstance(item, dict):
                return None
            target = item.get("target") if isinstance(item.get("target"), dict) else item
            device_id = str(target.get("deviceId") or target.get("device") or "")
            try:
                record = self.registry.get(device_id)
            except KeyError:
                resolved = self.resolver.resolve(device_id, self.registry.list())
                if not resolved.unique or resolved.device is None:
                    return None
                record = resolved.device
            if record.trust.value != "TRUSTED":
                return None
            objective = redact_objective(str(item.get("objective") or ""))
            if not objective:
                return None
            if self._capability_issue(record, objective) is not None:
                return None
            depends = item.get("dependsOn") if isinstance(item.get("dependsOn"), list) else []
            if any(not isinstance(dep, int) for dep in depends):
                return None
            missions.append(PlanMission(
                device_id=record.device_id,
                objective=objective,
                depends_on=[int(dep) for dep in depends],
                priority=str(item.get("priority") or priority),
                capabilities=sorted(required_capabilities(objective)),
            ))
        return FleetPlan("missions", redact_objective(str(proposed.get("goal") or raw)), missions=missions)


@dataclass
class _Resolved:
    kind: str
    device_id: str | None = None
    plan: FleetPlan | None = None


def required_capabilities(objective: str) -> set[str]:
    text = objective.casefold()
    needed = {"observe"}
    if any(word in text for word in ("type", "search", "message", "email", "summarize")):
        needed.add("type")
    if any(word in text for word in ("open", "launch")):
        needed.add("launchApp")
    if "screenshot" in text or "scroll" in text:
        needed.add("scroll" if "scroll" in text else "screenshots")
    if "screenshot" in text:
        needed.add("screenshots")
    if any(word in text for word in ("camera", "take a photo", "take photo")):
        needed.add("camera")
    if any(word in text for word in ("microphone", "record audio")):
        needed.add("microphone")
    for hint, capability in _APP_HINTS.items():
        if hint in text:
            needed.add(capability)
    needed.discard("shell")
    return needed
