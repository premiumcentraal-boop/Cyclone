"""Synthetic Command Center data for plan 76 (stage 0): the benchmark's data and the upgrade test's starting file.

Everything is made through the stores' own APIs, so every check, audit entry and index runs as it does for the owner.
Nothing is real: services come from a fixed list of example packages and domains, handles are numbered, phone ids are
``bench-phone-NNNN``, and no text holds a name, address, message or secret (the test-data policy in
``Cyclone V5 plan/76-test-data-policy.md``).
"""
from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Any

SERVICES = ("com.example.notes", "com.example.photos", "com.example.chat", "com.example.shop", "example.com",
            "shop.example.org", "com.example.video", "com.example.maps")
WORDS = ("plan", "review", "export", "weekly", "budget", "launch", "draft", "archive", "summary", "checklist",
         "lighthouse", "harbour", "orchard", "meadow")
GOALS = ("Open the settings app and read the battery level", "Open the notes app and make a note called weekly plan",
         "Open the calendar and read tomorrow's first event", "Open the clock and set a timer for five minutes")


@dataclass(frozen=True)
class Scale:
    accounts: int = 5_000
    tasks: int = 20_000
    rows: int = 10_000  # the per-table cap today (tables.MAX_ROWS); D8 asks for 100,000
    pages: int = 2_000
    phones: int = 1_000


#: The plan's design target where today's caps allow it (D8, plan 76 §3 and §12).
D8 = Scale()
#: Small enough for a unit test.
SMALL = Scale(accounts=30, tasks=40, rows=60, pages=12, phones=6)


def devices(count: int) -> list[dict[str, Any]]:
    return [{"deviceId": f"bench-phone-{i:04d}", "paired": True, "state": "ready"} for i in range(count)]


def seed(center: Any, scale: Scale, phones: list[dict[str, Any]], *, seed_value: int = 76) -> dict[str, Any]:
    """Fill ``center`` (a fresh CommandCenter whose device list returns ``phones``). Returns ids to query."""
    rng = random.Random(seed_value)
    accounts = []
    for i in range(scale.accounts):
        accounts.append(center.create_account({
            "service": SERVICES[i % len(SERVICES)], "handle": f"@acct-{i:05d}", "ownerBasis": ("mine", "company", "client")[i % 3],
            "twofa": ("none", "totp", "sms")[i % 3], "notes": f"{rng.choice(WORDS)} {rng.choice(WORDS)}"})["id"])
    for i in range(scale.tasks):
        center.create_task({"title": f"Task {i:05d}", "goal": rng.choice(GOALS),
                            "deviceId": phones[i % len(phones)]["deviceId"], "requestId": f"bench-task-{i:06d}"})
    table = center.tables.create({"title": "Bench accounts", "description": "Synthetic rows for the plan 76 benchmark."})
    title = next(p["id"] for p in center.tables.get(table["id"])["properties"] if p["type"] == "title")
    score = center.tables.add_property(table["id"], {"name": "Score", "type": "number"})
    score_id = next(p["id"] for p in score["properties"] if p["name"] == "Score")
    for i in range(scale.rows):
        center.tables.create_row(table["id"], {"cells": {title: f"{rng.choice(WORDS)} row {i:05d}", score_id: rng.randint(0, 1000)}})
    pages = []
    for i in range(scale.pages):
        page = center.pages.create({"title": f"{rng.choice(WORDS).title()} page {i:04d}"})
        body = [{"type": "p", "text": [{"t": " ".join(rng.choice(WORDS) for _ in range(12))}]} for _ in range(8)]
        pages.append(center.pages.update(page["id"], {"version": page["version"], "blocks": body})["id"])
    with center._lock:
        center.health.sweep(phones)
    return {"accounts": len(accounts), "tableId": table["id"], "pages": len(pages)}
