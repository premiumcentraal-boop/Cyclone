"""Plan 76, stage 0: every synthetic secret is refused at every place the Command Center accepts text.

A new text entry point joins ENTRY_POINTS in the change that adds it (test-data policy, rule 3).
"""
from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

import pytest

from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.desktop_runtime.v5_contract import INLINE_SECRET
from synthetic_secrets import CORPUS
from test_command_center import FakeContract

PHONES = [{"deviceId": "phone-a", "paired": True, "state": "ready"}]


@pytest.fixture()
def center(tmp_path: Path):
    c = CommandCenter(tmp_path / "cc.db", FakeContract(), lambda: PHONES)
    yield c
    c.stop()


def _account(c: CommandCenter, **extra: Any) -> dict[str, Any]:
    return c.create_account({"service": "com.example.notes", "handle": "@acct-00001", "ownerBasis": "mine", **extra})


def _table(c: CommandCenter) -> tuple[str, str]:
    table = c.tables.create({"title": "Corpus"})
    title = next(p["id"] for p in c.tables.get(table["id"])["properties"] if p["type"] == "title")
    return table["id"], title


def _page_text(c: CommandCenter, text: str) -> Any:
    page = c.pages.create({"title": "Corpus"})
    return c.pages.update(page["id"], {"version": page["version"], "blocks": [{"type": "p", "text": [{"t": text}]}]})


ENTRY_POINTS: dict[str, Callable[[CommandCenter, str], Any]] = {
    "account handle": lambda c, s: c.create_account({"service": "com.example.notes", "handle": s, "ownerBasis": "mine"}),
    "account notes": lambda c, s: _account(c, notes=s),
    "account notes, edited": lambda c, s: c.update_account(_account(c)["id"], {"notes": s}),
    "task title": lambda c, s: c.create_task({"title": s, "goal": "Open the notes app", "deviceId": "phone-a"}),
    "task goal": lambda c, s: c.create_task({"goal": f"Open the notes app {s}", "deviceId": "phone-a"}),
    "routine title": lambda c, s: c.create_routine({"title": s, "goal": "Check the notes app",
                                                    "schedule": {"kind": "every", "minutes": 60}}),
    "routine goal": lambda c, s: c.create_routine({"title": "Hourly", "goal": f"Check {s}",
                                                   "schedule": {"kind": "every", "minutes": 60}}),
    "page title": lambda c, s: c.pages.create({"title": s}),
    "page block text": _page_text,
    "table title": lambda c, s: c.tables.create({"title": s}),
    "table description": lambda c, s: c.tables.create({"title": "Corpus", "description": s}),
    "property name": lambda c, s: c.tables.add_property(_table(c)[0], {"name": s, "type": "text"}),
    "row title cell": lambda c, s: (lambda t: c.tables.create_row(t[0], {"cells": {t[1]: s}}))(_table(c)),
}


def test_the_corpus_matches_the_refusal_rule() -> None:
    for entry in CORPUS:
        assert INLINE_SECRET.search(entry), f"corpus entry is not secret-shaped: {entry!r}"


@pytest.mark.parametrize("where", sorted(ENTRY_POINTS))
@pytest.mark.parametrize("secret", CORPUS)
def test_every_secret_is_refused_everywhere(center: CommandCenter, where: str, secret: str) -> None:
    with pytest.raises(CommandError):
        ENTRY_POINTS[where](center, secret)


@pytest.mark.parametrize("where", sorted(ENTRY_POINTS))
def test_plain_text_is_accepted_everywhere(center: CommandCenter, where: str) -> None:
    """The refusal is about secrets, not about text: the same entry points take ordinary words."""
    ENTRY_POINTS[where](center, "weekly plan review")
