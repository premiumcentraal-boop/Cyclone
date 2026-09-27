from __future__ import annotations

import ipaddress
import re
import secrets
from typing import Any

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, RuntimeErrorCode

V5_CONTRACT_PROTOCOL = "cyclone.v5.run2.contract.v1"
V5_OPS = frozenset({
    "atlas.places",
    "atlas.get",
    "atlas.diff",
    "mapping.start",
    "mapping.pause",
    "mapping.stop",
    "mapping.status",
    "secrets.slots",
    "secrets.request",
    "ask.start",
    "ask.status",
    "apps.list",
    "runs.list",
    "runs.get",
    "runs.mark",
    "atlas.versions",
    "scenarios.list",
    "knowledge.get",
    "atlas.here",
    "share.status",
    "share.request",
    "lab.start",
    "lab.status",
    "lab.answer",
    "lab.record",
    "market.catalog",
    "market.install",
    "market.remove",
    "market.run",
    "learn.run",
    "skills.list",
})
ASK_STATES = frozenset({"idle", "working", "action-needed", "needs-secret", "done", "failed"})
ASK_MILESTONE_STATES = frozenset({"pending", "active", "done", "action-needed", "failed"})
ASK_STATUS_KEYS = frozenset({
    "taskId", "state", "title", "app", "currentMilestone", "milestones", "supportingCopy",
    "outcomeCopy", "sessionId", "displayId",
})
MAX_GOAL = 2000
PERSONAS = frozenset({"live", "mapping"})
MAPPING_STATES = frozenset({
    "idle",
    "running",
    "paused",
    "needs-secret",
    "human-control",
    "completed",
    "stopped",
    "failed",
})
FORBIDDEN_SECRET_KEYS = frozenset({
    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "api_key",
    "authorization", "cookie", "cvv", "credential", "typed_text", "typed_value",
})
FORBIDDEN_SECRET_TOKENS = frozenset({
    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "apikey",
    "authorization", "cookie", "cvv", "credential", "credentials", "typedtext", "typedvalue",
})
INLINE_SECRET = re.compile(
    r"(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(?:text|value))\s*[:=]"
)
PACKAGE_PLACE = re.compile(r"^package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
CHROME_PLACE = re.compile(r"^chrome:https?://[^\s/]+(?::[0-9]{1,5})?$")
SLOT = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,63}$")
REASON = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$")
JOB_ID = re.compile(r"^[A-Za-z0-9_-]{8,120}$")
WORKSPACE_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
CURSOR = re.compile(r"^c1:[a-f0-9]{20}:[0-9]+$")
STRUCTURAL_ID = re.compile(r"^[A-Za-z0-9._:-]{1,180}$")
SCREEN_ID = re.compile(r"^(?:page|screen):[A-Za-z0-9._:-]{1,173}$")
EDGE_ID = re.compile(r"^edge:[A-Za-z0-9._:-]{1,175}$")
MAPPING_JOB_KEYS = frozenset({
    "mappingJobId", "placeId", "persona", "state", "sessionId", "displayId", "plane",
    "controlRevision", "executionGeneration", "budget", "currentAtlasNodeId", "progress",
    "atlasStatus", "danger", "boundary", "startedAtEpochMs", "updatedAtEpochMs", "failureCode",
})
#: Whose account a mapping pass uses: own = the owner's account, look only; test = a test account.
MAPPING_IDENTITIES = frozenset({"own", "test"})
BUDGET_KEYS = frozenset({
    "maxNewScreens",
    "maxElapsedMs",
    "maxConsecutiveNonProgress",
    "maxAttemptsPerDoor",
})
PROGRESS_KEYS = frozenset({
    "newScreens",
    "verifiedMutations",
    "consecutiveNonProgress",
    "attemptedDoors",
    "remainingDarkRegions",
})
PLANE_KEYS = frozenset({
    "kind",
    "sessionId",
    "displayId",
    "workspaceId",
    "workspaceGeneration",
    "label",
})


def _normalized_key(value: str) -> str:
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value).lower().replace("-", "_").replace(" ", "_")


def _secret_name(value: str) -> bool:
    normalized = _normalized_key(value)
    compact = normalized.replace("_", "")
    return (
        normalized in FORBIDDEN_SECRET_KEYS
        or compact in FORBIDDEN_SECRET_TOKENS
        or any(part in FORBIDDEN_SECRET_TOKENS for part in normalized.split("_"))
    )


def reject_secret_payload(value: Any, path: str = "$") -> None:
    """Fail closed before a secret-bearing payload can be logged or forwarded."""
    if isinstance(value, dict):
        for key, nested in value.items():
            key_text = str(key)
            child = f"{path}.{key_text}"
            if _secret_name(key_text):
                raise DesktopRuntimeError(
                    RuntimeErrorCode.INVALID_REQUEST,
                    "Secret-bearing request payload rejected.",
                )
            reject_secret_payload(nested, child)
        return
    if isinstance(value, list):
        for index, nested in enumerate(value):
            reject_secret_payload(nested, f"{path}[{index}]")
        return
    if isinstance(value, str) and INLINE_SECRET.search(value):
        raise DesktopRuntimeError(
            RuntimeErrorCode.INVALID_REQUEST,
            "Secret-bearing request payload rejected.",
        )


def _is_int(value: Any, *, minimum: int = 0) -> bool:
    return type(value) is int and value >= minimum


def _validate_place_persona(args: dict[str, Any]) -> None:
    place_id = args.get("placeId")
    persona = args.get("persona")
    if not isinstance(place_id, str) or (
        PACKAGE_PLACE.fullmatch(place_id) is None and CHROME_PLACE.fullmatch(place_id) is None
    ):
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Invalid placeId.")
    if persona not in PERSONAS:
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "persona must be live or mapping.")


def _validate_mapping_plane(args: dict[str, Any], *, allow_execution_generation: bool) -> None:
    session_id = args.get("sessionId")
    display_id = args.get("displayId")
    if not isinstance(session_id, str) or not session_id.strip() or len(session_id) > 160:
        raise DesktopRuntimeError(RuntimeErrorCode.SESSION_REQUIRED, "sessionId is required for mapping.")
    if not _is_int(display_id):
        raise DesktopRuntimeError(
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
            "displayId is required and must be a non-negative integer.",
        )

    workspace_id = args.get("workspaceId")
    workspace_generation = args.get("workspaceGeneration")
    has_workspace = workspace_id is not None or workspace_generation is not None
    if has_workspace:
        if session_id != "default-foreground" or display_id != 0:
            raise DesktopRuntimeError(
                RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
                "Layer-2 mapping requires default-foreground/display 0.",
            )
        if not isinstance(workspace_id, str) or WORKSPACE_ID.fullmatch(workspace_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Invalid workspaceId.")
        if not _is_int(workspace_generation):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "workspaceGeneration is required.")
    elif session_id == "default-foreground":
        if display_id != 0:
            raise DesktopRuntimeError(
                RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
                "default-foreground mapping must use display 0.",
            )
    elif display_id <= 0:
        raise DesktopRuntimeError(
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
            "Named mapping sessions require a nonzero displayId.",
        )

    if "executionGeneration" in args:
        if not allow_execution_generation:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "executionGeneration is not accepted here.")
        if not _is_int(args.get("executionGeneration")):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "executionGeneration must be non-negative.")


def _validate_budget(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != BUDGET_KEYS:
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Mapping budget has an invalid shape.")
    limits = {
        "maxNewScreens": (1, 500),
        "maxElapsedMs": (1_000, 7_200_000),
        "maxConsecutiveNonProgress": (1, 100),
        "maxAttemptsPerDoor": (1, 20),
    }
    for key, (low, high) in limits.items():
        item = value.get(key)
        if not _is_int(item, minimum=low) or item > high:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{key} is outside its allowed range.")


def _validate_slot_presence_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    if set(value) != {"placeId", "persona", "slots"}:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots result is malformed.")
    if value.get("placeId") != args.get("placeId") or value.get("persona") != args.get("persona"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots identity mismatch.")
    slots = value.get("slots")
    if not isinstance(slots, dict):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.slots presence map is malformed.")
    for slot, present in slots.items():
        if not isinstance(slot, str) or SLOT.fullmatch(slot) is None or type(present) is not bool:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Secret slot presence must be boolean metadata only.")


def _validate_secret_request_ack(value: dict[str, Any], args: dict[str, Any]) -> None:
    reject_secret_payload(value)
    if set(value) != {"state", "request"} or value.get("state") != "needs-secret":
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.request acknowledgement is malformed.")
    request = value.get("request")
    if not isinstance(request, dict) or request != args:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android secrets.request metadata mismatch.")


def _reject_atlas_secret_fact_slots(value: Any) -> None:
    if not isinstance(value, dict):
        return
    screens = value.get("screens")
    if not isinstance(screens, list):
        return
    for screen in screens:
        if not isinstance(screen, dict):
            continue
        slots = screen.get("factSlots")
        if not isinstance(slots, list):
            continue
        for slot in slots:
            if not isinstance(slot, dict):
                continue
            name = slot.get("name")
            if isinstance(name, str) and _secret_name(name):
                raise DesktopRuntimeError(
                    RuntimeErrorCode.PROTOCOL_MISMATCH,
                    "Android Atlas result attempted to expose a secret fact slot.",
                )


def _validate_atlas_diff(value: dict[str, Any], args: dict[str, Any]) -> None:
    expected = {"placeId", "persona", "since", "cursor", "resyncRequired", "changes"}
    if set(value) != expected:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff result is malformed.")
    if value.get("placeId") != args.get("placeId") or value.get("persona") != args.get("persona"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff identity mismatch.")
    expected_since = args.get("since")
    if value.get("since") != expected_since:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff cursor echo mismatch.")
    cursor = value.get("cursor")
    if not isinstance(cursor, str) or CURSOR.fullmatch(cursor) is None:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff cursor is malformed.")
    if type(value.get("resyncRequired")) is not bool or not isinstance(value.get("changes"), list):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.diff metadata is malformed.")
    if value["resyncRequired"] and value["changes"]:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Resync-required diff must not fabricate changes.")

    allowed_base = {"cursor", "entity", "change", "id"}
    for change in value["changes"]:
        if not isinstance(change, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change must be an object.")
        if not allowed_base.issubset(change):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change is incomplete.")
        if not set(change).issubset(allowed_base | {"mapStatus", "fromScreenId", "toScreenId", "layout"}):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change exposed an unknown field.")
        if not isinstance(change["cursor"], str) or CURSOR.fullmatch(change["cursor"]) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change cursor is malformed.")
        if change.get("entity") not in {"place", "screen", "edge"} or change.get("change") not in {"upsert", "remove"}:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff change type is invalid.")
        change_id = change.get("id")
        if not isinstance(change_id, str) or STRUCTURAL_ID.fullmatch(change_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff structural id is invalid.")
        entity = change["entity"]
        if entity == "place" and change_id != "place":
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas place diff id must be literal place.")
        if entity == "screen" and SCREEN_ID.fullmatch(change_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas screen diff id must be page:/screen:.")
        if entity == "edge" and EDGE_ID.fullmatch(change_id) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas edge diff id must use edge:.")
        if "mapStatus" in change and change["mapStatus"] not in {"unmapped", "partial", "mapped", "stale", "blocked"}:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff mapStatus is invalid.")
        endpoints = ("fromScreenId" in change, "toScreenId" in change)
        if endpoints[0] != endpoints[1]:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff edge topology is incomplete.")
        for key in ("fromScreenId", "toScreenId"):
            if key in change and (
                not isinstance(change[key], str) or SCREEN_ID.fullmatch(change[key]) is None
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff topology id is invalid.")
        if entity == "edge" and not all(endpoints):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas edge diff requires topology.")
        if entity != "edge" and any(endpoints):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Only Atlas edge diffs may carry topology.")
        if entity == "place" and "layout" in change:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas place diff cannot carry layout.")
        if entity == "screen" and "mapStatus" in change:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas screen diff cannot carry mapStatus.")
        if entity == "edge" and ("mapStatus" in change or "layout" in change):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas edge diff carries topology only.")
        if "layout" in change:
            layout = change["layout"]
            if not isinstance(layout, dict) or set(layout) != {"x", "y"}:
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff layout is malformed.")
            if type(layout["x"]) not in (int, float) or type(layout["y"]) not in (int, float):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Atlas diff layout must be numeric.")


def _validate_mapping_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    # alpha.38+ phones add `identity` (own/test, null when idle); older phones omit it.
    if set(value) not in (MAPPING_JOB_KEYS, MAPPING_JOB_KEYS | {"identity"}):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping result is malformed.")
    if "identity" in value and value["identity"] not in (MAPPING_IDENTITIES | {None}):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping identity is invalid.")
    if value.get("state") not in MAPPING_STATES:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping state is invalid.")
    if value.get("sessionId") != args.get("sessionId") or value.get("displayId") != args.get("displayId"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping plane identity mismatch.")
    plane = value.get("plane")
    if not isinstance(plane, dict) or set(plane) != PLANE_KEYS:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping plane is malformed.")
    if plane.get("sessionId") != value.get("sessionId") or plane.get("displayId") != value.get("displayId"):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping plane disagrees with result identity.")
    if value["state"] == "idle":
        if value.get("mappingJobId") is not None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Idle mapping status must not invent a job.")
    else:
        if not isinstance(value.get("mappingJobId"), str) or JOB_ID.fullmatch(value["mappingJobId"]) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mappingJobId is malformed.")
        if not isinstance(value.get("placeId"), str) or (
            PACKAGE_PLACE.fullmatch(value["placeId"]) is None and CHROME_PLACE.fullmatch(value["placeId"]) is None
        ):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping placeId is malformed.")
        if value.get("persona") not in PERSONAS:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping persona is invalid.")
        if not _is_int(value.get("controlRevision")):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping controlRevision is invalid.")

    budget = value.get("budget")
    if budget is not None:
        _validate_budget(budget)
    progress = value.get("progress")
    if not isinstance(progress, dict) or set(progress) != PROGRESS_KEYS:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping progress is malformed.")
    if not all(_is_int(progress.get(key)) for key in PROGRESS_KEYS):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping progress counters are invalid.")
    node_id = value.get("currentAtlasNodeId")
    if node_id is not None and (not isinstance(node_id, str) or SCREEN_ID.fullmatch(node_id) is None):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping node id must be page:/screen:.")
    if value.get("atlasStatus") not in {None, "partial", "mapped"}:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android mapping atlasStatus is invalid.")


def _lab_mission(mission_id: Any) -> str:
    if not isinstance(mission_id, str) or not LAB_MISSION_ID.match(mission_id):
        raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "missionId is malformed.")
    return mission_id


def _validate_ask_foreground(args: dict[str, Any]) -> None:
    """Ask from Glass runs on the phone's main screen only; never a guessed named display."""
    session_id = args.get("sessionId")
    if not isinstance(session_id, str) or not session_id:
        raise DesktopRuntimeError(RuntimeErrorCode.SESSION_REQUIRED, "sessionId is required for ask.")
    if session_id != "default-foreground" or not _is_int(args.get("displayId")) or args.get("displayId") != 0:
        raise DesktopRuntimeError(
            RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
            "Ask runs on default-foreground / display 0.",
        )


def _validate_ask_response(op: str, value: dict[str, Any]) -> None:
    if op == "ask.start":
        if set(value) != {"accepted", "sessionId", "displayId"} or value.get("accepted") is not True:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.start acknowledgement is malformed.")
        return
    if set(value) != ASK_STATUS_KEYS or value.get("state") not in ASK_STATES:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.status result is malformed.")
    milestones = value.get("milestones")
    if not isinstance(milestones, list) or len(milestones) > 8:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.status milestones are malformed.")
    for milestone in milestones:
        if (
            not isinstance(milestone, dict)
            or set(milestone) != {"label", "state"}
            or not isinstance(milestone.get("label"), str)
            or milestone.get("state") not in ASK_MILESTONE_STATES
        ):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android ask.status milestone is malformed.")


APP_KEYS = frozenset({
    "placeId", "kind", "label", "packageName", "origin", "installed", "installedVersion", "mapStatus", "rooms",
    "doors", "lastVerifiedAt", "needsRemap", "personas", "mappedVersions",
})
APP_KINDS = frozenset({"package", "chrome-origin"})
MAP_STATUSES = frozenset({"unmapped", "partial", "mapped", "stale"})
MAX_APPS = 600


def _validate_version(value: Any, *, extra: frozenset[str] = frozenset()) -> None:
    if not isinstance(value, dict) or not {"versionName", "versionCode"} <= set(value) <= {"versionName", "versionCode"} | extra:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app version is malformed.")
    if value["versionName"] is not None and not isinstance(value["versionName"], str):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app versionName is malformed.")
    if value["versionCode"] is not None and not _is_int(value["versionCode"]):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app versionCode is malformed.")


def _validate_apps_list(value: dict[str, Any]) -> None:
    """apps.list carries package facts and map counts only: no screens, doors, selectors or user content."""
    if set(value) != {"apps", "truncated"} or not isinstance(value.get("apps"), list) or not isinstance(value.get("truncated"), bool):
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android apps.list result is malformed.")
    if len(value["apps"]) > MAX_APPS:
        raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android apps.list returned too many apps.")
    for app in value["apps"]:
        if not isinstance(app, dict) or not APP_KEYS <= set(app) <= APP_KEYS | {"scenarios"}:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app entry is malformed.")
        if "scenarios" in app:  # alpha.14+: scenario health counts for the Apps page
            counts = app["scenarios"]
            if not isinstance(counts, dict) or set(counts) != {"passing", "warning", "critical", "untested"} or not all(
                _is_int(counts[key]) for key in counts
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app scenario counts are malformed.")
        place_id = app["placeId"]
        if not isinstance(place_id, str) or not place_id.startswith(("package:", "chrome:")) or len(place_id) > 512:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app placeId is malformed.")
        if app["kind"] not in APP_KINDS or app["mapStatus"] not in MAP_STATUSES:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app kind or status is unknown.")
        if not isinstance(app["label"], str) or not app["label"] or len(app["label"]) > 80:
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app label is malformed.")
        if not _is_int(app["rooms"]) or not _is_int(app["doors"]) or not isinstance(app["needsRemap"], bool):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app counts are malformed.")
        if app["installed"] is not None and not isinstance(app["installed"], bool):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app installed flag is malformed.")
        if app["installedVersion"] is not None:
            _validate_version(app["installedVersion"])
        if not isinstance(app["mappedVersions"], list) or not isinstance(app["personas"], list):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app versions are malformed.")
        for version in app["mappedVersions"]:
            _validate_version(version, extra=frozenset({"doors"}))
        for persona in app["personas"]:
            if (
                not isinstance(persona, dict)
                or set(persona) != {"persona", "mapStatus", "rooms", "doors", "lastVerifiedAt"}
                or persona["persona"] not in {"live", "mapping"}
                or persona["mapStatus"] not in MAP_STATUSES
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android app persona is malformed.")


RUN_ID = re.compile(r"^[A-Za-z0-9_-]{4,120}$")
RUN_STATUSES = frozenset({"running", "suspended", "completed", "failed", "cancelled"})
RUN_FILTERS = frozenset({"all", "failed", "completed", "stopped"})
RUN_SUMMARY_KEYS = frozenset({
    "runId", "goal", "model", "status", "startedAt", "endedAt", "durationMs", "decisions", "stepCount", "metrics", "cause",
})
RUN_DETAIL_KEYS = RUN_SUMMARY_KEYS | {"result", "stepsTruncated", "steps"}
RUN_CAUSE_KEYS = frozenset({"kind", "stepIndex", "headline", "detail", "fix"})
RUN_STEP_KEYS = frozenset({
    "index", "startedAt", "endedAt", "title", "action", "pageId", "outcome", "verification", "recovery", "vision",
    "eventsTruncated", "events",
})
RUN_EVENT_KEYS = frozenset({"at", "kind", "text", "code", "ok", "detail"})
# Run record v2 (Glass alpha.4): optional so phones from alpha.8-alpha.10 keep working.
RUN_SUMMARY_V2_KEYS = frozenset({"mapSteps", "modelSteps", "places"})
RUN_OPTIONAL_KEYS = frozenset({"expected", "clauses", "ledger"})
RUN_STEP_V2_KEYS = frozenset({"roomId", "roomAfter", "placeId", "appVersion", "decisionSource"})
RUN_STEP_OPTIONAL_KEYS = frozenset({"expectedRoomId"})
RUN_CLAUSE_KEYS = frozenset({"id", "text", "place", "status", "proof"})
RUN_LEDGER_KEYS = frozenset({"key", "value", "sourcePlace", "sourceRoom", "persona", "readAtMs"})
NAV_PLACE_ID = re.compile(r"^(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?)$")
MASKED_FACT = re.compile(r"^[^\s*@]\*{3}(?:@[A-Za-z0-9.-]+\.[A-Za-z]{2,})?$")
RUN_PLACE_KEYS = frozenset({"placeId", "appVersion", "route"})
ROOM_ID = re.compile(r"^screen:[a-z_]{1,40}:[0-9a-f]{8,64}$")
RUN_PLACE_ID = re.compile(r"^package:[A-Za-z][A-Za-z0-9_.]{1,150}$")
APP_VERSION = re.compile(r"^[A-Za-z0-9._+-]{1,40}$")
MAX_RUN_PLACES = 8
MAX_ROUTE_ROOMS = 60
STEP_OUTCOMES = frozenset({"ok", "failed", "unverified", "recovered", "info"})
MAX_RUNS = 200
MAX_RUN_STEPS = 200
MAX_STEP_EVENTS = 40


def _bad_run(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android run {message} is malformed.")


def _short_text(value: Any, limit: int, *, nullable: bool = False) -> bool:
    if value is None:
        return nullable
    return isinstance(value, str) and len(value) <= limit


def _optional_match(value: Any, pattern: re.Pattern[str]) -> bool:
    return value is None or (isinstance(value, str) and bool(pattern.match(value)))


def _validate_run_summary(run: Any, keys: frozenset[str]) -> None:
    if not isinstance(run, dict) or not keys <= set(run) <= keys | RUN_SUMMARY_V2_KEYS | RUN_OPTIONAL_KEYS:
        raise _bad_run("summary")
    if "expected" in run and not isinstance(run["expected"], bool):
        raise _bad_run("expected")
    if "clauses" in run:
        clauses = run["clauses"]
        if not isinstance(clauses, list) or len(clauses) > 16:
            raise _bad_run("clauses")
        seen = set()
        for clause in clauses:
            if not isinstance(clause, dict) or set(clause) != RUN_CLAUSE_KEYS:
                raise _bad_run("clause")
            if not isinstance(clause["id"], str) or not re.fullmatch(r"clause-(?:[1-9]|1[0-6])", clause["id"]) or clause["id"] in seen:
                raise _bad_run("clause id")
            seen.add(clause["id"])
            if not _short_text(clause["text"], 500) or not _short_text(clause["proof"], 400, nullable=True):
                raise _bad_run("clause text")
            if not _optional_match(clause["place"], NAV_PLACE_ID) or clause["status"] not in (
                "pending", "active", "verified", "needs-approval", "failed"
            ):
                raise _bad_run("clause state")
    if "ledger" in run:
        ledger = run["ledger"]
        if not isinstance(ledger, list) or len(ledger) > 4:
            raise _bad_run("ledger")
        seen = set()
        for fact in ledger:
            if not isinstance(fact, dict) or set(fact) != RUN_LEDGER_KEYS:
                raise _bad_run("ledger fact")
            if not isinstance(fact["key"], str) or fact["key"] not in (
                "signed-in-email", "found-username", "thread-with", "connected-network"
            ) or fact["key"] in seen:
                raise _bad_run("ledger key")
            seen.add(fact["key"])
            if not isinstance(fact["value"], str) or len(fact["value"]) > 256 or not MASKED_FACT.fullmatch(fact["value"]):
                raise _bad_run("unmasked ledger")
            if not isinstance(fact["sourcePlace"], str) or not NAV_PLACE_ID.fullmatch(fact["sourcePlace"]):
                raise _bad_run("ledger place")
            if not isinstance(fact["sourceRoom"], str) or not ROOM_ID.fullmatch(fact["sourceRoom"]):
                raise _bad_run("ledger room")
            if fact["persona"] != "live" or not _is_int(fact["readAtMs"]):
                raise _bad_run("ledger provenance")
    if RUN_SUMMARY_V2_KEYS & set(run):
        if set(run) & RUN_SUMMARY_V2_KEYS != RUN_SUMMARY_V2_KEYS:
            raise _bad_run("record v2")
        if not _is_int(run["mapSteps"]) or not _is_int(run["modelSteps"]):
            raise _bad_run("map/model steps")
        places = run["places"]
        if not isinstance(places, list) or len(places) > MAX_RUN_PLACES:
            raise _bad_run("places")
        for place in places:
            if not isinstance(place, dict) or set(place) != RUN_PLACE_KEYS:
                raise _bad_run("place")
            if not isinstance(place["placeId"], str) or not RUN_PLACE_ID.match(place["placeId"]):
                raise _bad_run("place id")
            if not _optional_match(place["appVersion"], APP_VERSION):
                raise _bad_run("app version")
            route = place["route"]
            if not isinstance(route, list) or len(route) > MAX_ROUTE_ROOMS or not all(
                isinstance(room, str) and ROOM_ID.match(room) for room in route
            ):
                raise _bad_run("route")
    if not isinstance(run["runId"], str) or not RUN_ID.match(run["runId"]) or run["status"] not in RUN_STATUSES:
        raise _bad_run("identity")
    if not _short_text(run["goal"], 500) or not _short_text(run["model"], 120):
        raise _bad_run("text")
    for key in ("startedAt", "durationMs", "decisions", "stepCount"):
        if not _is_int(run[key]):
            raise _bad_run(key)
    if run["endedAt"] is not None and not _is_int(run["endedAt"]):
        raise _bad_run("endedAt")
    metrics = run["metrics"]
    if not isinstance(metrics, dict) or not all(isinstance(k, str) and _is_int(v) for k, v in metrics.items()) or len(metrics) > 12:
        raise _bad_run("metrics")
    cause = run["cause"]
    if cause is not None:
        if not isinstance(cause, dict) or set(cause) != RUN_CAUSE_KEYS:
            raise _bad_run("cause")
        if not isinstance(cause["kind"], str) or not re.fullmatch(r"[a-z][a-z-]{1,40}", cause["kind"]):
            raise _bad_run("cause kind")
        if cause["stepIndex"] is not None and not _is_int(cause["stepIndex"]):
            raise _bad_run("cause step")
        if not all(_short_text(cause[key], 400) for key in ("headline", "detail", "fix")):
            raise _bad_run("cause text")


def _validate_runs_list(value: dict[str, Any]) -> None:
    if set(value) != {"runs"} or not isinstance(value["runs"], list) or len(value["runs"]) > MAX_RUNS:
        raise _bad_run("list")
    for run in value["runs"]:
        _validate_run_summary(run, RUN_SUMMARY_KEYS)


def _validate_run_detail(value: dict[str, Any], args: dict[str, Any]) -> None:
    _validate_run_summary(value, RUN_DETAIL_KEYS)
    if value["runId"] != args.get("runId"):
        raise _bad_run("id mismatch")
    if not _short_text(value["result"], 1500) or not isinstance(value["stepsTruncated"], bool):
        raise _bad_run("result")
    steps = value["steps"]
    if not isinstance(steps, list) or len(steps) > MAX_RUN_STEPS:
        raise _bad_run("steps")
    for step in steps:
        if not isinstance(step, dict) or not RUN_STEP_KEYS <= set(step) <= RUN_STEP_KEYS | RUN_STEP_V2_KEYS | RUN_STEP_OPTIONAL_KEYS:
            raise _bad_run("step")
        if "expectedRoomId" in step and not _optional_match(step["expectedRoomId"], ROOM_ID):
            raise _bad_run("expected room")
        if step["outcome"] not in STEP_OUTCOMES:
            raise _bad_run("step")
        if RUN_STEP_V2_KEYS & set(step):
            if set(step) & RUN_STEP_V2_KEYS != RUN_STEP_V2_KEYS:
                raise _bad_run("step record v2")
            if not (_optional_match(step["roomId"], ROOM_ID) and _optional_match(step["roomAfter"], ROOM_ID)
                    and _optional_match(step["placeId"], RUN_PLACE_ID) and _optional_match(step["appVersion"], APP_VERSION)):
                raise _bad_run("step location")
            if step["decisionSource"] not in (None, "map", "model"):
                raise _bad_run("decision source")
        if not all(_is_int(step[key]) for key in ("index", "startedAt", "endedAt")) or not isinstance(step["vision"], bool):
            raise _bad_run("step numbers")
        if not _short_text(step["title"], 200) or not all(
            _short_text(step[key], 120, nullable=True) for key in ("action", "pageId", "verification", "recovery")
        ):
            raise _bad_run("step text")
        events = step["events"]
        if not isinstance(events, list) or len(events) > MAX_STEP_EVENTS or not isinstance(step["eventsTruncated"], bool):
            raise _bad_run("step events")
        for event in events:
            if not isinstance(event, dict) or set(event) != RUN_EVENT_KEYS or not _is_int(event["at"]):
                raise _bad_run("event")
            if not _short_text(event["kind"], 40) or not _short_text(event["text"], 360):
                raise _bad_run("event text")
            if not _short_text(event["code"], 120, nullable=True) or not _short_text(event["detail"], 600, nullable=True):
                raise _bad_run("event detail")
            if event["ok"] is not None and not isinstance(event["ok"], bool):
                raise _bad_run("event ok")


KNOWLEDGE_PLACE_ID = re.compile(r"^package:[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$")
VERSION_ROW_KEYS = frozenset({"versionName", "versionCode", "installed", "doors", "rooms", "failingDoors", "lastSeenAt"})
STALE_DOOR_KEYS = frozenset({"edgeId", "fromScreenId", "toScreenId", "versionName", "versionCode"})
SCENARIO_KEYS = frozenset({
    "scenarioId", "title", "startScreenId", "endScreenId", "route", "steps", "danger", "health", "lastVerifiedAt", "appVersion", "runs",
})
SCENARIO_HEALTH = frozenset({"passing", "warning", "critical", "untested"})
SCENARIO_KINDS = frozenset({"reach", "sign-in", "signed-in"})
SCENARIO_ID = re.compile(r"^sc_[0-9a-f]{18}$")


def _bad_knowledge(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android {message} is malformed.")


def _version_fields(value: dict[str, Any]) -> bool:
    return _short_text(value.get("versionName"), 64, nullable=True) and (
        value.get("versionCode") is None or _is_int(value.get("versionCode"))
    )


def _validate_atlas_versions(value: dict[str, Any], args: dict[str, Any]) -> None:
    """Version facts and structural ids only: no labels, selectors or screen content."""
    keys = {"placeId", "installedVersion", "needsRemap", "versions", "staleDoorCount", "staleDoors"}
    if set(value) != keys or value["placeId"] != args.get("placeId") or not isinstance(value["needsRemap"], bool):
        raise _bad_knowledge("atlas.versions")
    installed = value["installedVersion"]
    if installed is not None and (not isinstance(installed, dict) or set(installed) != {"versionName", "versionCode"} or not _version_fields(installed)):
        raise _bad_knowledge("installed version")
    versions = value["versions"]
    if not isinstance(versions, list) or len(versions) > 12:
        raise _bad_knowledge("versions")
    for row in versions:
        if not isinstance(row, dict) or set(row) != VERSION_ROW_KEYS or not _version_fields(row) or not isinstance(row["installed"], bool):
            raise _bad_knowledge("version row")
        if not all(_is_int(row[key]) for key in ("doors", "rooms", "failingDoors", "lastSeenAt")):
            raise _bad_knowledge("version counts")
    stale = value["staleDoors"]
    if not _is_int(value["staleDoorCount"]) or not isinstance(stale, list) or len(stale) > 20:
        raise _bad_knowledge("stale doors")
    for door in stale:
        if not isinstance(door, dict) or set(door) != STALE_DOOR_KEYS or not _version_fields(door):
            raise _bad_knowledge("stale door")
        if not EDGE_ID.fullmatch(str(door["edgeId"])) or not all(
            isinstance(door[key], str) and SCREEN_ID.fullmatch(door[key]) for key in ("fromScreenId", "toScreenId")
        ):
            raise _bad_knowledge("stale door ids")


def _validate_scenarios(value: dict[str, Any], args: dict[str, Any]) -> None:
    if set(value) != {"placeId", "persona", "entryScreenId", "scenarios"} or value["placeId"] != args.get("placeId"):
        raise _bad_knowledge("scenarios.list")
    if value["persona"] not in {"live", "mapping"}:
        raise _bad_knowledge("scenario persona")
    entry = value["entryScreenId"]
    if entry is not None and (not isinstance(entry, str) or not SCREEN_ID.fullmatch(entry)):
        raise _bad_knowledge("entry room")
    scenarios = value["scenarios"]
    if not isinstance(scenarios, list) or len(scenarios) > 24:
        raise _bad_knowledge("scenarios")
    for scenario in scenarios:
        if not isinstance(scenario, dict) or set(scenario) - {"kind"} != SCENARIO_KEYS:
            raise _bad_knowledge("scenario")
        if "kind" in scenario and scenario["kind"] not in SCENARIO_KINDS:  # alpha.15+: sign-in scenarios
            raise _bad_knowledge("scenario kind")
        if not isinstance(scenario["scenarioId"], str) or not SCENARIO_ID.match(scenario["scenarioId"]):
            raise _bad_knowledge("scenario id")
        if not _short_text(scenario["title"], 80) or scenario["health"] not in SCENARIO_HEALTH or not isinstance(scenario["danger"], bool):
            raise _bad_knowledge("scenario fields")
        route = scenario["route"]
        if not isinstance(route, list) or not 2 <= len(route) <= 40 or not all(isinstance(room, str) and SCREEN_ID.fullmatch(room) for room in route):
            raise _bad_knowledge("scenario route")
        if route[0] != scenario["startScreenId"] or route[-1] != scenario["endScreenId"] or scenario["steps"] != len(route) - 1:
            raise _bad_knowledge("scenario ends")
        if scenario["lastVerifiedAt"] is not None and not _is_int(scenario["lastVerifiedAt"]):
            raise _bad_knowledge("scenario verified")
        if not _short_text(scenario["appVersion"], 64, nullable=True):
            raise _bad_knowledge("scenario version")
        runs = scenario["runs"]
        if not isinstance(runs, list) or len(runs) > 5:
            raise _bad_knowledge("scenario runs")
        for run in runs:
            if not isinstance(run, dict) or set(run) != {"runId", "status", "startedAt"}:
                raise _bad_knowledge("scenario run")
            if not isinstance(run["runId"], str) or not RUN_ID.match(run["runId"]) or run["status"] not in RUN_STATUSES or not _is_int(run["startedAt"]):
                raise _bad_knowledge("scenario run fields")


SLOT_PLACE_ID = re.compile(r"^(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?://[A-Za-z0-9.-]{1,190}(?::\d{1,5})?)$")
GUARDED_DANGERS = frozenset({"payment", "send-public", "delete-account", "logout-all", "permission"})
SLOT_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9._-]{0,63}$")


def _validate_knowledge_summary(value: dict[str, Any]) -> None:
    """Slot presence (never values), skill/automation names and counts, Atlas totals."""
    if set(value) - {"guarded"} != {"vault", "skills", "automations", "atlas"}:
        raise _bad_knowledge("knowledge.get")
    vault = value["vault"]
    if not isinstance(vault, dict) or set(vault) != {"slotCount", "setCount", "slots"}:
        raise _bad_knowledge("vault")
    if not _is_int(vault["slotCount"]) or not _is_int(vault["setCount"]) or not isinstance(vault["slots"], list) or len(vault["slots"]) > 200:
        raise _bad_knowledge("vault counts")
    for slot in vault["slots"]:
        if not isinstance(slot, dict) or set(slot) != {"placeId", "persona", "slot", "set", "updatedAt"}:
            raise _bad_knowledge("vault slot")
        if not isinstance(slot["placeId"], str) or not SLOT_PLACE_ID.match(slot["placeId"]) or slot["persona"] not in {"live", "mapping"}:
            raise _bad_knowledge("vault slot place")
        if not isinstance(slot["slot"], str) or not SLOT_NAME.match(slot["slot"]) or not isinstance(slot["set"], bool):
            raise _bad_knowledge("vault slot name")
        if slot["updatedAt"] is not None and not _is_int(slot["updatedAt"]):
            raise _bad_knowledge("vault slot time")
    for key, fields in (("skills", {"id", "name", "steps", "enabled", "version"}), ("automations", {"id", "name", "trigger", "steps", "enabled"})):
        rows = value[key]
        if not isinstance(rows, list) or len(rows) > 100:
            raise _bad_knowledge(key)
        for row in rows:
            if not isinstance(row, dict) or set(row) != fields or not _short_text(row["id"], 80) or not _short_text(row["name"], 80):
                raise _bad_knowledge(f"{key} row")
            if not _is_int(row["steps"]) or not isinstance(row["enabled"], bool):
                raise _bad_knowledge(f"{key} fields")
            if key == "skills" and not _is_int(row["version"]):
                raise _bad_knowledge("skill version")
            if key == "automations" and (not isinstance(row["trigger"], str) or not re.fullmatch(r"[a-z_]{1,40}", row["trigger"])):
                raise _bad_knowledge("automation trigger")
    atlas = value["atlas"]
    if not isinstance(atlas, dict) or set(atlas) != {"places", "rooms", "doors"} or not all(_is_int(atlas[k]) for k in atlas):
        raise _bad_knowledge("atlas totals")
    if "guarded" in value:  # alpha.15+: the never-pay list, counts per app and danger
        guarded = value["guarded"]
        if not isinstance(guarded, list) or len(guarded) > 200:
            raise _bad_knowledge("guarded")
        for row in guarded:
            if not isinstance(row, dict) or set(row) - {"roomIds"} != {"placeId", "label", "persona", "danger", "doors", "rooms"}:
                raise _bad_knowledge("guarded row")
            rooms = row.get("roomIds", [])
            if not isinstance(rooms, list) or len(rooms) > 10 or not all(isinstance(room, str) and SCREEN_ID.fullmatch(room) for room in rooms):
                raise _bad_knowledge("guarded rooms")
            if not isinstance(row["placeId"], str) or not SLOT_PLACE_ID.match(row["placeId"]) or row["persona"] not in {"live", "mapping"}:
                raise _bad_knowledge("guarded place")
            if row["danger"] not in GUARDED_DANGERS or not _short_text(row["label"], 80):
                raise _bad_knowledge("guarded danger")
            if not _is_int(row["doors"]) or not _is_int(row["rooms"]) or row["doors"] < 0 or row["rooms"] < 0:
                raise _bad_knowledge("guarded counts")


def validate_android_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
    """Keep a phone bug from turning into PC/model secret or mapping authority."""
    if op == "secrets.slots":
        _validate_slot_presence_response(value, args)
        return value
    if op == "secrets.request":
        _validate_secret_request_ack(value, args)
        return value

    reject_secret_payload(value)
    if op == "atlas.places":
        if set(value) != {"places"} or not isinstance(value.get("places"), list):
            raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android atlas.places result is malformed.")
        for summary in value["places"]:
            if not isinstance(summary, dict):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android Atlas place summary is malformed.")
            _reject_atlas_secret_fact_slots(summary)
        return value
    if op == "atlas.get":
        _reject_atlas_secret_fact_slots(value)
        return value
    if op == "atlas.diff":
        _validate_atlas_diff(value, args)
        return value
    if op in {"mapping.start", "mapping.pause", "mapping.stop", "mapping.status"}:
        _validate_mapping_response(value, args)
        return value
    if op in {"ask.start", "ask.status"}:
        _validate_ask_response(op, value)
        return value
    if op == "apps.list":
        _validate_apps_list(value)
        return value
    if op == "runs.list":
        _validate_runs_list(value)
        return value
    if op == "runs.get":
        _validate_run_detail(value, args)
        return value
    if op == "atlas.versions":
        _validate_atlas_versions(value, args)
        return value
    if op == "scenarios.list":
        _validate_scenarios(value, args)
        return value
    if op == "runs.mark":
        if set(value) != {"runId", "expected"} or value["runId"] != args.get("runId") or not isinstance(value["expected"], bool):
            raise _bad_run("mark")
        return value
    if op == "knowledge.get":
        _validate_knowledge_summary(value)
        return value
    if op == "share.status":
        _validate_share_status(value)
        return value
    if op == "share.request":
        if set(value) != {"prompted", "sharing"} or not all(isinstance(value[k], bool) for k in value):
            raise _bad_knowledge("share.request")
        return value
    if op in LAB_OPS:
        _validate_lab_response(op, value, args)
        return value
    if op in MARKET_OPS:
        _validate_market_response(op, value, args)
        return value
    if op == "learn.run":
        _validate_learn_response(value, args)
        return value
    if op == "skills.list":
        _validate_skills_response(value)
        return value
    if op == "atlas.here":
        if set(value) != {"placeId", "roomId", "appVersion", "observedAt"}:
            raise _bad_knowledge("atlas.here")
        if value["placeId"] is not None and (not isinstance(value["placeId"], str) or not KNOWLEDGE_PLACE_ID.match(value["placeId"])):
            raise _bad_knowledge("here place")
        if value["roomId"] is not None and (not isinstance(value["roomId"], str) or not SCREEN_ID.fullmatch(value["roomId"])):
            raise _bad_knowledge("here room")
        if not _short_text(value["appVersion"], 40, nullable=True) or (value["observedAt"] is not None and not _is_int(value["observedAt"])):
            raise _bad_knowledge("here facts")
        return value
    raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Unsupported V5 contract operation.")


LAB_OPS = frozenset({"lab.start", "lab.status", "lab.answer", "lab.record"})
LAB_MISSION_ID = re.compile(r"^m[a-z0-9]{6,40}$")
LAB_RUN_ID = re.compile(r"^[A-Za-z0-9_-]{6,80}$")
LAB_STATUSES = frozenset({"running", "waiting", "completed", "gave_up", "failed", "cancelled", "paused", "interrupted"})
LAB_MOMENT_KINDS = frozenset({"question", "values", "approval", "secret", "handover"})
LAB_ANSWERS = frozenset({"reply", "fill", "decline", "stop"})
LAB_RECORD_KEYS = frozenset({
    "missionId", "goal", "status", "live", "summary", "evidence", "turns", "workingMs", "resumes", "createdAt",
    "updatedAt", "modelId", "modelLabel", "usage", "traceId", "lab", "metrics", "events", "app",
})


def _bad_lab(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android lab result is malformed: {message}.")


def _validate_lab_moment(moment: Any) -> None:
    if moment is None:
        return
    if not isinstance(moment, dict) or set(moment) != {"kind", "text", "choices", "fields", "requestId"}:
        raise _bad_lab("moment")
    if moment["kind"] not in LAB_MOMENT_KINDS or not _short_text(moment["text"], 300):
        raise _bad_lab("moment kind")
    if not isinstance(moment["choices"], list) or len(moment["choices"]) > 6 or not all(_short_text(c, 80) for c in moment["choices"]):
        raise _bad_lab("moment choices")
    fields = moment["fields"]
    if not isinstance(fields, list) or len(fields) > 8:
        raise _bad_lab("moment fields")
    for field in fields:
        if not isinstance(field, dict) or set(field) != {"label", "kind"} or not _short_text(field["label"], 60) or not _short_text(field["kind"], 20):
            raise _bad_lab("moment field")
    if moment["requestId"] is not None and not _short_text(moment["requestId"], 80):
        raise _bad_lab("moment request")


def _validate_lab_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> None:
    """The phone's lab answers are facts about one mission; nothing in them can carry a secret or a command."""
    if op == "lab.start":
        if set(value) != {"accepted", "missionId", "taskId"} or value["accepted"] is not True:
            raise _bad_lab("start")
        if not isinstance(value["missionId"], str) or not LAB_MISSION_ID.match(value["missionId"]) or value["taskId"] != f"mission-{value['missionId']}":
            raise _bad_lab("start id")
        return
    if value.get("missionId") != args.get("missionId") and op != "lab.answer":
        raise _bad_lab("mission id")
    if op == "lab.status":
        if set(value) != {"missionId", "status", "live", "turns", "workingMs", "costUsd", "moment"}:
            raise _bad_lab("status")
        if value["status"] not in LAB_STATUSES or not isinstance(value["live"], bool):
            raise _bad_lab("status state")
        if not _is_int(value["turns"]) or not _is_int(value["workingMs"]) or not isinstance(value["costUsd"], (int, float)):
            raise _bad_lab("status numbers")
        _validate_lab_moment(value["moment"])
        return
    if op == "lab.answer":
        if set(value) != {"handled", "detail"} or not isinstance(value["handled"], bool) or not _short_text(value["detail"], 200):
            raise _bad_lab("answer")
        return
    if set(value) != LAB_RECORD_KEYS or value["status"] not in LAB_STATUSES:
        raise _bad_lab("record")
    for key, limit in (("goal", 600), ("summary", 600), ("evidence", 600), ("modelId", 120), ("modelLabel", 120)):
        if not _short_text(value[key], limit):
            raise _bad_lab(key)
    for key in ("turns", "workingMs", "resumes", "createdAt", "updatedAt"):
        if not _is_int(value[key]):
            raise _bad_lab(key)
    if not isinstance(value["usage"], dict) or not isinstance(value["metrics"], dict) or not isinstance(value["app"], dict):
        raise _bad_lab("usage/metrics/app")
    if value["lab"] is not None and (not isinstance(value["lab"], dict) or not isinstance(value["lab"].get("variant"), dict)):
        raise _bad_lab("lab tag")
    events = value["events"]
    if not isinstance(events, list) or len(events) > 20 or not all(isinstance(e, dict) and _short_text(e.get("text"), 200) for e in events):
        raise _bad_lab("events")


LEARN_KEYS = frozenset({"runId", "learned", "alreadyLearned", "sentence", "apps", "refusal"})
LEARN_APP_KEYS = frozenset({"package", "label", "screens", "newScreens", "controls", "transitions"})
LEARN_REFUSALS = frozenset({"RUN_NOT_FOUND", "ASK_BUSY", "NOTHING_TO_LEARN"})


def _bad_learn(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android learn result is malformed: {message}.")


def _validate_learn_response(value: dict[str, Any], args: dict[str, Any]) -> None:
    """Learn reports counts and app labels only: never a screen's content, a typed value or a selector."""
    if set(value) != LEARN_KEYS or value["runId"] != args.get("runId"):
        raise _bad_learn("keys")
    if not isinstance(value["learned"], bool) or not isinstance(value["alreadyLearned"], bool):
        raise _bad_learn("flags")
    if not isinstance(value["sentence"], str) or len(value["sentence"]) > 600:
        raise _bad_learn("sentence")
    apps = value["apps"]
    if not isinstance(apps, list) or len(apps) > 20:
        raise _bad_learn("apps")
    for app in apps:
        if not isinstance(app, dict) or set(app) != LEARN_APP_KEYS or not _short_text(app["package"], 200) or not _short_text(app["label"], 60):
            raise _bad_learn("app")
        if not all(_is_int(app[key]) for key in ("screens", "newScreens", "controls", "transitions")):
            raise _bad_learn("counts")
    refusal = value["refusal"]
    if value["learned"]:
        if refusal is not None or not value["sentence"]:
            raise _bad_learn("learned")
    elif (not isinstance(refusal, dict) or set(refusal) != {"code", "message"} or refusal["code"] not in LEARN_REFUSALS
          or not _short_text(refusal["message"], 200) or apps):
        raise _bad_learn("refusal")


SKILL_ID = re.compile(r"^you\.[a-f0-9]{12}$")
SKILL_GROUNDS = frozenset({"grounded", "partial", "needs-recheck", "not-grounded"})
SKILL_KEYS = frozenset({"skillId", "name", "placeId", "ground", "detail", "routeMoves", "route", "finishSteps", "savedAt"})


def _bad_skills(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Phone skills.list response is invalid: {message}.")


def _validate_skills_response(value: dict[str, Any]) -> None:
    """Saved skills: names, structural screen titles, health and counts only; never a goal's inputs or screen content."""
    if set(value) != {"skills", "truncated"} or not isinstance(value["truncated"], bool):
        raise _bad_skills("keys")
    skills = value["skills"]
    if not isinstance(skills, list) or len(skills) > 200:
        raise _bad_skills("skills")
    for skill in skills:
        if not isinstance(skill, dict) or set(skill) != SKILL_KEYS:
            raise _bad_skills("skill keys")
        if not isinstance(skill["skillId"], str) or not SKILL_ID.match(skill["skillId"]) or not _short_text(skill["name"], 80):
            raise _bad_skills("skill id")
        if skill["placeId"] is not None and (not isinstance(skill["placeId"], str) or not KNOWLEDGE_PLACE_ID.match(skill["placeId"])):
            raise _bad_skills("place")
        if skill["ground"] not in SKILL_GROUNDS or not _short_text(skill["detail"], 200):
            raise _bad_skills("ground")
        if skill["routeMoves"] is not None and not _is_int(skill["routeMoves"]):
            raise _bad_skills("moves")
        if not _is_int(skill["finishSteps"]) or (skill["savedAt"] is not None and not _is_int(skill["savedAt"])):
            raise _bad_skills("counts")
        route = skill["route"]
        if not isinstance(route, list) or len(route) > 12 or (route and skill["placeId"] is None):
            raise _bad_skills("route")
        for point in route:
            if not isinstance(point, dict) or set(point) != {"title", "screenId"} or not _short_text(point["title"], 60):
                raise _bad_skills("waypoint")
            if point["screenId"] is not None and (not isinstance(point["screenId"], str) or not SCREEN_ID.fullmatch(point["screenId"])):
                raise _bad_skills("waypoint screen")


MARKET_OPS = frozenset({"market.catalog", "market.install", "market.remove", "market.run"})
MARKET_LISTING_ID = re.compile(r"^[a-z0-9][a-z0-9.-]{2,63}$")
MARKET_LISTING_KEYS = frozenset({
    "id", "kind", "version", "name", "publisher", "summary", "category", "glyph", "goal", "inputs", "apps", "does",
    "asksFirst", "suggestFor", "featured", "added", "savedInputs", "runs", "lastRunAt",
})
MARKET_CONNECTION_STATES = frozenset({"connected", "needs_setup", "off"})


def _bad_market(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, f"Android marketplace result is malformed: {message}.")


def _text_list(value: Any, limit: int, size: int) -> bool:
    return isinstance(value, list) and len(value) <= size and all(_short_text(item, limit) for item in value)


def _validate_market_response(op: str, value: dict[str, Any], args: dict[str, Any]) -> None:
    """Listings are data the phone shows; nothing in them may carry a secret, a command or unbounded text."""
    if op == "market.catalog":
        if set(value) != {"listings", "suggestions", "installedCount", "connections"}:
            raise _bad_market("catalog")
        listings = value["listings"]
        if not isinstance(listings, list) or len(listings) > 500:
            raise _bad_market("listings")
        for item in listings:
            if not isinstance(item, dict) or set(item) != MARKET_LISTING_KEYS or not MARKET_LISTING_ID.match(str(item.get("id"))):
                raise _bad_market("listing")
            if item["kind"] not in {"recipe", "connection"} or not isinstance(item["added"], bool) or not _is_int(item["runs"]):
                raise _bad_market("listing kind")
            for key, limit in (("name", 60), ("summary", 160), ("category", 40), ("glyph", 8), ("goal", 600), ("version", 20)):
                if not _short_text(item[key], limit):
                    raise _bad_market(key)
            for key in ("apps", "does", "asksFirst", "suggestFor"):
                if not _text_list(item[key], 160, 20):
                    raise _bad_market(key)
            if not isinstance(item["inputs"], list) or len(item["inputs"]) > 12:
                raise _bad_market("inputs")
            saved = item["savedInputs"]
            if saved is not None and (not isinstance(saved, dict) or not all(_short_text(v, 200) for v in saved.values())):
                raise _bad_market("saved inputs")
        if not isinstance(value["suggestions"], list) or not all(
            isinstance(s, dict) and set(s) == {"id", "reason"} and _short_text(s["reason"], 120) for s in value["suggestions"]
        ):
            raise _bad_market("suggestions")
        if not _is_int(value["installedCount"]):
            raise _bad_market("count")
        connections = value["connections"]
        if not isinstance(connections, list) or len(connections) > 20:
            raise _bad_market("connections")
        for item in connections:
            if (not isinstance(item, dict) or set(item) != {"id", "name", "glyph", "state", "detail", "where"}
                    or item["state"] not in MARKET_CONNECTION_STATES or not _short_text(item["detail"], 160)):
                raise _bad_market("connection")
        return
    if value.get("id") != args.get("id"):
        raise _bad_market("id")
    if op == "market.install":
        if set(value) != {"id", "added", "inputs"} or value["added"] is not True or not isinstance(value["inputs"], dict):
            raise _bad_market("install")
    elif op == "market.remove":
        if set(value) != {"id", "removed"} or not isinstance(value["removed"], bool):
            raise _bad_market("remove")
    elif set(value) != {"id", "started"} or value["started"] is not True:
        raise _bad_market("run")


SHARE_PHONE_ID = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
SHARE_PROTOCOL = "cyclone-lan-share-v1"


def _validate_share_status(value: dict[str, Any]) -> None:
    """Wi-Fi share facts: sharing flag, a port and private IPv4 addresses only (never public or loopback)."""
    if set(value) != {"sharing", "port", "addresses", "phoneId", "protocol"} or not isinstance(value["sharing"], bool):
        raise _bad_knowledge("share.status")
    if value["protocol"] != SHARE_PROTOCOL or not isinstance(value["phoneId"], str) or (value["phoneId"] and not SHARE_PHONE_ID.match(value["phoneId"])):
        raise _bad_knowledge("share protocol")
    addresses = value["addresses"]
    if not isinstance(addresses, list) or len(addresses) > 4:
        raise _bad_knowledge("share addresses")
    for address in addresses:
        try:
            ip = ipaddress.IPv4Address(address) if isinstance(address, str) else None
        except ipaddress.AddressValueError:
            ip = None
        if ip is None or not ip.is_private or ip.is_loopback:
            raise _bad_knowledge("share address")
    if value["sharing"]:
        if not _is_int(value["port"], minimum=1) or value["port"] > 65535 or not addresses:
            raise _bad_knowledge("share port")
    elif value["port"] is not None or addresses:
        raise _bad_knowledge("share idle")


class V5ContractService:
    """Constrained PC bridge for Atlas/Vault/mapping metadata operations.

    This service creates no PC-side Atlas, diff journal, mapping state, or Vault truth. Every
    successful result comes from the authenticated Android Gateway.
    """

    PROTOCOL = V5_CONTRACT_PROTOCOL

    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet

    def atlas_places(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "atlas.places", {})

    def apps_list(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "apps.list", {})

    def runs_list(self, device_id: str, limit: int = 50, run_filter: str = "all") -> dict[str, Any]:
        if not _is_int(limit, minimum=1) or limit > MAX_RUNS or run_filter not in RUN_FILTERS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.list takes limit 1..200 and a known filter.")
        return self._call(device_id, "runs.list", {"limit": limit, "filter": run_filter})

    def runs_get(self, device_id: str, run_id: str) -> dict[str, Any]:
        if not isinstance(run_id, str) or not RUN_ID.match(run_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runId is malformed.")
        return self._call(device_id, "runs.get", {"runId": run_id})

    def runs_mark(self, device_id: str, run_id: str, expected: bool) -> dict[str, Any]:
        if not isinstance(run_id, str) or not RUN_ID.match(run_id) or not isinstance(expected, bool):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.mark takes a runId and expected true/false.")
        return self._call(device_id, "runs.mark", {"runId": run_id, "expected": expected})

    def atlas_here(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "atlas.here", {})

    def share_status(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "share.status", {})

    def share_request(self, device_id: str, pc_label: str | None = None) -> dict[str, Any]:
        args = {"pcLabel": pc_label[:60]} if isinstance(pc_label, str) and pc_label.strip() else {}
        return self._call(device_id, "share.request", args)

    def knowledge_summary(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "knowledge.get", {})

    def atlas_versions(self, device_id: str, place_id: str) -> dict[str, Any]:
        if not isinstance(place_id, str) or not KNOWLEDGE_PLACE_ID.match(place_id) or len(place_id) > 200:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Versions are for apps (package:…).")
        return self._call(device_id, "atlas.versions", {"placeId": place_id})

    def scenarios_list(self, device_id: str, place_id: str, persona: str = "mapping") -> dict[str, Any]:
        if not isinstance(place_id, str) or not KNOWLEDGE_PLACE_ID.match(place_id) or len(place_id) > 200 or persona not in {"live", "mapping"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Scenarios are for apps (package:…) and a known persona.")
        return self._call(device_id, "scenarios.list", {"placeId": place_id, "persona": persona})

    def atlas_get(self, device_id: str, place_id: str, persona: str) -> dict[str, Any]:
        args = {"placeId": place_id, "persona": persona}
        _validate_place_persona(args)
        return self._call(device_id, "atlas.get", args)

    def atlas_diff(
        self,
        device_id: str,
        place_id: str,
        persona: str,
        since: str | None = None,
    ) -> dict[str, Any]:
        args: dict[str, Any] = {"placeId": place_id, "persona": persona, "since": since}
        _validate_place_persona(args)
        if since is not None and (not isinstance(since, str) or not since or len(since) > 128):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "since must be a bounded phone-issued cursor.")
        return self._call(device_id, "atlas.diff", args)

    def secret_slots(self, device_id: str, place_id: str, persona: str) -> dict[str, Any]:
        args = {"placeId": place_id, "persona": persona}
        _validate_place_persona(args)
        return self._call(device_id, "secrets.slots", args)

    def secret_request(
        self,
        device_id: str,
        *,
        place_id: str,
        persona: str,
        slot: str,
        reason: str,
        extra: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        args: dict[str, Any] = {
            "placeId": place_id,
            "persona": persona,
            "slot": slot,
            "reason": reason,
        }
        if extra:
            args.update(extra)
        reject_secret_payload(args)
        _validate_place_persona(args)
        if set(args) != {"placeId", "persona", "slot", "reason"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unexpected secrets.request field.")
        if not isinstance(slot, str) or SLOT.fullmatch(slot) is None:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "slot must be metadata only.")
        if not isinstance(reason, str) or REASON.fullmatch(reason) is None or INLINE_SECRET.search(reason):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "reason must be a bounded-safe-label.")
        return self._call(device_id, "secrets.request", args)

    def lab_start(self, device_id: str, goal: str, run_id: str, variant: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(goal, str) or not goal.strip() or len(goal) > 2000 or INLINE_SECRET.search(goal):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A lab goal is 1..2000 characters without secrets.")
        if not isinstance(run_id, str) or not LAB_RUN_ID.match(run_id) or not isinstance(variant, dict):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "lab.start needs a runId and a variant.")
        return self._call(device_id, "lab.start", {"goal": goal.strip(), "runId": run_id, "variant": variant})

    def lab_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return self._call(device_id, "lab.status", {"missionId": _lab_mission(mission_id)})

    def lab_record(self, device_id: str, mission_id: str) -> dict[str, Any]:
        return self._call(device_id, "lab.record", {"missionId": _lab_mission(mission_id)})

    def lab_answer(self, device_id: str, mission_id: str, action: str, *, text: str | None = None,
                   values: dict[str, str] | None = None) -> dict[str, Any]:
        """The lab plays the owner: reply, fill, decline or stop. It never approves and never sends a secret."""
        if action not in LAB_ANSWERS:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "The lab can reply, fill, decline or stop; it never approves.")
        args: dict[str, Any] = {"missionId": _lab_mission(mission_id), "action": action}
        if action == "reply":
            if not isinstance(text, str) or not text.strip() or len(text) > 500 or INLINE_SECRET.search(text):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A lab reply is 1..500 characters without secrets.")
            args["text"] = text.strip()
        if action == "fill":
            if not isinstance(values, dict) or not 1 <= len(values) <= 8 or not all(
                isinstance(k, str) and isinstance(v, str) and len(k) <= 60 and len(v) <= 300 for k, v in values.items()
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "A lab fill is 1..8 short text values.")
            args["values"] = values
        return self._call(device_id, "lab.answer", args)

    def learn_run(self, device_id: str, run_id: str) -> dict[str, Any]:
        """Learn everything one run saw and did, on the phone. Returns counts only."""
        if not isinstance(run_id, str) or not RUN_ID.match(run_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runId is malformed.")
        return self._call(device_id, "learn.run", {"runId": run_id})

    def skills_list(self, device_id: str) -> dict[str, Any]:
        """The owner's saved skills and where each lives on the map (plan 23). Titles, health and counts only."""
        return self._call(device_id, "skills.list", {})

    def market_catalog(self, device_id: str) -> dict[str, Any]:
        return self._call(device_id, "market.catalog", {})

    def market_change(self, device_id: str, op: str, listing_id: str, inputs: dict[str, Any] | None = None) -> dict[str, Any]:
        """Add, remove or run one listing on the phone. The phone validates inputs again and refuses secrets."""
        if op not in {"market.install", "market.remove", "market.run"}:
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Unknown marketplace change.")
        if not isinstance(listing_id, str) or not MARKET_LISTING_ID.match(listing_id):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Listing id is malformed.")
        args: dict[str, Any] = {"id": listing_id}
        if op != "market.remove" and inputs:
            if not isinstance(inputs, dict) or len(inputs) > 12 or not all(
                isinstance(k, str) and len(k) <= 32 and isinstance(v, str) and len(v) <= 200 for k, v in inputs.items()
            ):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Inputs are up to 12 short text values.")
            if any(INLINE_SECRET.search(f"{k}: {v}") or _secret_name(k) for k, v in inputs.items()):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "Secrets never go into a recipe; Cyclone asks on the phone.")
            args["inputs"] = inputs
        return self._call(device_id, op, args)

    def forward(self, device_id: str, op: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        """Typed forwarding seam. There is no generic Android-op passthrough or PC mapping truth."""
        if op not in V5_OPS:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Unsupported V5 contract operation.")
        args = dict(payload or {})
        reject_secret_payload(args)

        if op == "atlas.places":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.places takes no arguments.")
            return self.atlas_places(device_id)
        if op == "apps.list":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "apps.list takes no arguments.")
            return self.apps_list(device_id)
        if op == "runs.list":
            if not set(args) <= {"limit", "filter"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.list takes limit and filter only.")
            return self.runs_list(device_id, args.get("limit", 50), args.get("filter", "all"))
        if op == "runs.get":
            if set(args) != {"runId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.get takes runId only.")
            return self.runs_get(device_id, args["runId"])
        if op == "skills.list":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "skills.list takes no arguments.")
            return self.skills_list(device_id)
        if op == "learn.run":
            if set(args) != {"runId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "learn.run takes runId only.")
            return self.learn_run(device_id, args["runId"])
        if op == "runs.mark":
            if set(args) != {"runId", "expected"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "runs.mark takes runId and expected only.")
            return self.runs_mark(device_id, args["runId"], args["expected"])
        if op == "atlas.here":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.here takes no arguments.")
            return self.atlas_here(device_id)
        if op == "share.status":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "share.status takes no arguments.")
            return self.share_status(device_id)
        if op == "share.request":
            if set(args) - {"pcLabel"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "share.request takes pcLabel only.")
            return self.share_request(device_id, args.get("pcLabel"))
        if op == "knowledge.get":
            if args:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "knowledge.get takes no arguments.")
            return self.knowledge_summary(device_id)
        if op == "atlas.versions":
            if set(args) != {"placeId"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.versions takes placeId only.")
            return self.atlas_versions(device_id, args["placeId"])
        if op == "scenarios.list":
            if not {"placeId"} <= set(args) <= {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "scenarios.list takes placeId and persona only.")
            return self.scenarios_list(device_id, args["placeId"], args.get("persona", "mapping"))
        if op == "atlas.get":
            if set(args) != {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.get requires placeId/persona only.")
            return self.atlas_get(device_id, str(args["placeId"]), str(args["persona"]))
        if op == "atlas.diff":
            if set(args) != {"placeId", "persona", "since"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "atlas.diff requires placeId/persona/since.")
            return self.atlas_diff(device_id, str(args["placeId"]), str(args["persona"]), args.get("since"))
        if op == "secrets.slots":
            if set(args) != {"placeId", "persona"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "secrets.slots requires placeId/persona only.")
            return self.secret_slots(device_id, str(args["placeId"]), str(args["persona"]))
        if op == "secrets.request":
            if set(args) != {"placeId", "persona", "slot", "reason"}:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "secrets.request requires safe metadata only.")
            return self.secret_request(
                device_id,
                place_id=str(args["placeId"]),
                persona=str(args["persona"]),
                slot=str(args["slot"]),
                reason=str(args["reason"]),
            )

        if op == "ask.start":
            if not set(args).issubset({"goal", "sessionId", "displayId"}):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "ask.start takes goal plus plane identity only.")
            _validate_ask_foreground(args)
            goal = args.get("goal")
            if not isinstance(goal, str) or not goal.strip() or len(goal) > MAX_GOAL:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "goal must be 1..2000 characters of text.")
            return self._call(device_id, op, args)
        if op == "ask.status":
            if not set(args).issubset({"sessionId", "displayId"}):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "ask.status takes plane identity only.")
            _validate_ask_foreground(args)
            return self._call(device_id, op, args)

        if op == "mapping.start":
            allowed = {
                "placeId", "persona", "sessionId", "displayId", "workspaceId", "workspaceGeneration",
                "executionGeneration", "budget", "resumeJobId", "identity",
            }
            if not set(args).issubset(allowed):
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "mapping.start has an unexpected field.")
            _validate_mapping_plane(args, allow_execution_generation=True)
            resume_job_id = args.get("resumeJobId")
            if resume_job_id is not None:
                if not isinstance(resume_job_id, str) or JOB_ID.fullmatch(resume_job_id) is None:
                    raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "resumeJobId is invalid.")
                if any(key in args for key in ("placeId", "persona", "budget", "identity")):
                    raise DesktopRuntimeError(
                        RuntimeErrorCode.INVALID_REQUEST,
                        "mapping.start resume accepts resumeJobId plus plane identity only.",
                    )
            else:
                _validate_place_persona(args)
                if "budget" in args:
                    _validate_budget(args["budget"])
                if "identity" in args and args["identity"] not in MAPPING_IDENTITIES:
                    raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "identity must be own (look only) or test.")
            return self._call(device_id, op, args)

        allowed_command = {"mappingJobId", "sessionId", "displayId", "workspaceId", "workspaceGeneration"}
        if not set(args).issubset(allowed_command):
            raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, f"{op} has an unexpected field.")
        _validate_mapping_plane(args, allow_execution_generation=False)
        if op in {"mapping.pause", "mapping.stop"}:
            mapping_job_id = args.get("mappingJobId")
            if not isinstance(mapping_job_id, str) or JOB_ID.fullmatch(mapping_job_id) is None:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "mappingJobId is required.")
        elif "mappingJobId" in args:
            mapping_job_id = args["mappingJobId"]
            if not isinstance(mapping_job_id, str) or JOB_ID.fullmatch(mapping_job_id) is None:
                raise DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, "mappingJobId is invalid.")
        return self._call(device_id, op, args)

    def _paired(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if not session.credential:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REQUIRED, "Pair this phone before V5 contract access.")
        return session

    def _call(self, device_id: str, op: str, args: dict[str, Any]) -> dict[str, Any]:
        reject_secret_payload(args)
        session = self._paired(device_id)
        try:
            value = session.bridge().request(op, args, request_id=f"v5-{secrets.token_urlsafe(18)}")
            if not isinstance(value, dict):
                raise DesktopRuntimeError(RuntimeErrorCode.PROTOCOL_MISMATCH, "Android V5 result must be an object.")
            return validate_android_response(op, value, args)
        except BridgeOperationError as exc:
            mapping = {
                "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
                "PROTOCOL_MISMATCH": RuntimeErrorCode.PROTOCOL_MISMATCH,
                "INVALID_REQUEST": RuntimeErrorCode.INVALID_REQUEST,
                "SECRET_PAYLOAD_REJECTED": RuntimeErrorCode.INVALID_REQUEST,
                "SESSION_REQUIRED": RuntimeErrorCode.SESSION_REQUIRED,
                "SESSION_DISPLAY_MISMATCH": RuntimeErrorCode.SESSION_DISPLAY_MISMATCH,
                "HUMAN_HAS_CONTROL": RuntimeErrorCode.HUMAN_HAS_CONTROL,
                "STALE_CONTROL_REVISION": RuntimeErrorCode.STALE_CONTROL_REVISION,
                "MAPPING_PLANE_BUSY": RuntimeErrorCode.MAPPING_PLANE_BUSY,
                "MAPPING_JOB_NOT_FOUND": RuntimeErrorCode.MAPPING_JOB_NOT_FOUND,
                "RUN_NOT_FOUND": RuntimeErrorCode.RUN_NOT_FOUND,
                "MAPPING_INVALID_STATE": RuntimeErrorCode.MAPPING_INVALID_STATE,
                "ASK_BUSY": RuntimeErrorCode.ASK_BUSY,
                "OVERLAY_UNAVAILABLE": RuntimeErrorCode.OVERLAY_UNAVAILABLE,
            }
            raise DesktopRuntimeError(
                mapping.get(exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE),
                f"Android rejected {op}.",
            ) from exc
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_DISCONNECTED,
                "Phone disconnected from Cyclone Gateway.",
                retryable=True,
            ) from exc
