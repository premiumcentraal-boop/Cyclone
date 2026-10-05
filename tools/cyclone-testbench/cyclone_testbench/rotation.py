"""What to run next, so a round-the-clock loop covers everything and keeps re-checking what broke.

Each batch is built in this order:
  1. re-checks: missions of open, regressed or fixing findings (critical first), so a fix is confirmed or reopened;
     only on a phone build newer than the one the finding was last seen on (alpha 108: re-running a known failure on
     the same build teaches nothing and cost half of every round);
  2. the campaign's next slot (round robin over its slots, oldest first), filled with that slot's missions that have
     run least, so every mission gets its turn;
  3. anything never run yet, if there is room.
The batch never repeats a mission, and it stays within the campaign's batch size. Missions with an open finding on the
current build are left out of the slots too (listed under `skipped`), until a new build arrives.
"""
from __future__ import annotations

from collections import Counter
from typing import Any

from .findings import SEVERITY_ORDER

DEFAULT_CAMPAIGN = {
    "name": "round-the-clock",
    "batchSize": 10,
    "recheck": 4,
    "repetitions": 1,
    "variants": [{"name": "default"}],
    "slots": [
        {"name": "smoke", "suites": ["smoke", "tb-smoke"]},
        {"name": "judgement", "suites": ["judgement"]},
        {"name": "safety", "suites": ["safety"]},
        {"name": "everyday", "suites": ["everyday"]},
        {"name": "robust", "suites": ["robust", "dutch"]},
        {"name": "core", "suites": ["core"]},
        {"name": "multi", "suites": ["multiapp", "multiapp2", "long"]},
        {"name": "hands", "suites": ["hands", "hands2", "steer2", "divert"]},
    ],
}


def validate_campaign(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("a campaign is an object")
    campaign = {**DEFAULT_CAMPAIGN, **raw}
    if not isinstance(campaign["slots"], list) or not campaign["slots"]:
        raise ValueError("a campaign needs slots")
    for slot in campaign["slots"]:
        if not isinstance(slot, dict) or not isinstance(slot.get("name"), str) or not (slot.get("suites") or slot.get("missions")):
            raise ValueError("each slot has a name and suites or missions")
    for key, low, high in (("batchSize", 1, 60), ("recheck", 0, 20), ("repetitions", 1, 20)):
        if not isinstance(campaign[key], int) or not low <= campaign[key] <= high:
            raise ValueError(f"{key} is {low}..{high}")
    return campaign


def next_slot(campaign: dict[str, Any], runs: list[dict[str, Any]]) -> dict[str, Any]:
    """The slot whose last run is oldest (never run first, then in campaign order)."""
    last: dict[str, int] = {}
    for run in runs:
        slot = str(run.get("slot") or "")
        last[slot] = max(last.get(slot, 0), int(run.get("createdAt") or 0))
    return min(enumerate(campaign["slots"]), key=lambda pair: (last.get(pair[1]["name"], -1), pair[0]))[1]


def plan_batch(campaign: dict[str, Any], catalog: list[dict[str, Any]], runs: list[dict[str, Any]],
               ledger: list[dict[str, Any]], *, installed: set[str] | None = None, app_version: str | None = None) -> dict[str, Any]:
    known = {m["id"]: m for m in catalog}
    run_count: Counter[str] = Counter()
    for run in runs:
        run_count.update(m for m in run.get("missions") or [])
    size = campaign["batchSize"]
    chosen: list[str] = []
    reasons: dict[str, str] = {}
    open_states = {"open", "regressed", "fixing"}
    known_failing = {str(f.get("missionId")): f for f in ledger
                     if app_version and f.get("status") in open_states and f.get("area") not in {"infra", "speed"}
                     and f.get("lastVersion") == app_version}
    skipped = {m: f"open {f.get('id')} on this build ({app_version})" for m, f in known_failing.items() if m in known}

    def add(mission_id: str, why: str) -> None:
        if mission_id in known_failing:
            return
        if mission_id in known and mission_id not in chosen and len(chosen) < size:
            if installed is not None and not set(known[mission_id].get("apps") or []) <= installed:
                return
            chosen.append(mission_id)
            reasons[mission_id] = why

    rechecks = sorted((f for f in ledger if f.get("status") in {"open", "regressed", "fixing"} and f.get("area") != "infra"),
                      key=lambda f: (SEVERITY_ORDER.get(f.get("severity"), 9), -int(f.get("lastSeen") or 0)))
    for finding in rechecks:
        if sum(1 for r in reasons.values() if r.startswith("re-check")) >= campaign["recheck"]:
            break
        add(str(finding.get("missionId")), f"re-check {finding.get('id')} ({finding.get('severity')}) on a new build")

    slot = next_slot(campaign, runs)
    members = [m["id"] for m in catalog
               if m["id"] in set(slot.get("missions") or []) or set(m.get("suites") or []) & set(slot.get("suites") or [])]
    for mission_id in sorted(members, key=lambda m: (run_count[m], m)):
        add(mission_id, f"slot {slot['name']}")
    for mission_id in sorted(known, key=lambda m: (run_count[m], m)):
        if run_count[mission_id] == 0:
            add(mission_id, "never run")
    return {"slot": slot["name"], "missions": chosen, "reasons": reasons, "skipped": skipped,
            "name": f"{campaign['name']} · {slot['name']}", "variants": campaign["variants"], "repetitions": campaign["repetitions"]}
