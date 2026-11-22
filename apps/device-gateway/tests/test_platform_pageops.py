"""Plan 76, stage 2 (run 2a): pages as trees of versioned blocks, edited through operations."""
from __future__ import annotations

import copy
import json
import random
import sqlite3
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.pageops import OpConflict, apply_ops, diff_ops, normalize_spans, spans_of, units
from cyclone_device_gateway.command.pages import MAX_NEST, validate_blocks
from cyclone_device_gateway.command.store import MIGRATIONS
from test_command_center import TOKEN, FakeContract


@pytest.fixture()
def center(tmp_path: Path):
    c = CommandCenter(tmp_path / "cc.db", FakeContract(), lambda: [])
    yield c
    c.stop()


def para(block_id: str, text: str, **extra) -> dict:
    return {"id": block_id, "type": "p", "text": [{"t": text}] if text else [], **extra}


def strip(blocks: list[dict]) -> list[dict]:
    """A tree without versions, for comparing content."""
    out = []
    for b in blocks:
        c = {k: v for k, v in b.items() if k not in ("v", "children")}
        if b.get("children"):
            c["children"] = strip(b["children"])
        out.append(c)
    return out


# ---------------------------------------------------------------- each operation

def test_every_operation_does_what_it_says(center):
    page = center.pages.create({"title": "Ops", "blocks": [para("bone001", "Hello world"), para("btwo001", "Second")]})
    pid = page["id"]
    after = center.pages.apply(pid, {"ops": [
        {"op": "text_insert", "id": "bone001", "v": 1, "at": 6, "text": [{"t": "big ", "b": True}]},
        {"op": "mark", "id": "bone001", "v": 1, "from": 0, "to": 5, "mark": "i", "on": True},
        {"op": "text_delete", "id": "btwo001", "v": 1, "at": 0, "len": 3},
        {"op": "set_type", "id": "btwo001", "v": 1, "type": "todo"},
        {"op": "set", "id": "btwo001", "v": 1, "props": {"checked": True}},
        {"op": "insert", "block": {"id": "bkid0001", "type": "bullet", "text": [{"t": "nested"}]}, "parent": "bone001", "after": None},
        {"op": "page", "title": "Renamed"},
    ]})
    one, two = after["blocks"]
    assert one["text"] == [{"t": "Hello", "i": True}, {"t": " "}, {"t": "big ", "b": True}, {"t": "world"}]
    assert one["children"][0]["id"] == "bkid0001" and one["children"][0]["v"] == 1
    assert two == {"id": "btwo001", "type": "todo", "v": 2, "text": [{"t": "ond"}], "checked": True}
    assert one["v"] == 2, "a block gains one version per batch, whatever the number of operations on it"
    assert after["title"] == "Renamed" and after["version"] == page["version"] + 1

    moved = center.pages.apply(pid, {"ops": [{"op": "move", "id": "bkid0001", "v": 1, "parent": None, "after": "btwo001"}]})
    assert [b["id"] for b in moved["blocks"]] == ["bone001", "btwo001", "bkid0001"] and "children" not in moved["blocks"][0]
    gone = center.pages.apply(pid, {"ops": [{"op": "delete", "id": "bone001", "v": 2}]})
    assert [b["id"] for b in gone["blocks"]] == ["btwo001", "bkid0001"]


def test_a_stale_version_or_a_vanished_anchor_refuses_the_whole_batch(center):
    page = center.pages.create({"blocks": [para("bone001", "A"), para("btwo001", "B")]})
    pid = page["id"]
    center.pages.apply(pid, {"ops": [{"op": "text_insert", "id": "bone001", "v": 1, "at": 1, "text": [{"t": "!"}]}]})
    for ops in (
        [{"op": "text_insert", "id": "btwo001", "v": 1, "at": 0, "text": [{"t": "x"}]},
         {"op": "text_delete", "id": "bone001", "v": 1, "at": 0, "len": 1}],                          # bone001 is now v2
        [{"op": "insert", "block": para("bnew0001", "n"), "after": "bgone001"}],                      # anchor gone
        [{"op": "delete", "id": "bgone001", "v": 1}],                                                 # block gone
    ):
        with pytest.raises(OpConflict):
            center.pages.apply(pid, {"ops": ops})
    unchanged = center.pages.get(pid)
    assert strip(unchanged["blocks"]) == [{"id": "bone001", "type": "p", "text": [{"t": "A!"}]}, {"id": "btwo001", "type": "p", "text": [{"t": "B"}]}]


def test_malformed_operations_and_secrets_are_refused(center):
    page = center.pages.create({"blocks": [para("bone001", "A"), {"id": "bdiv0001", "type": "divider"}]})
    pid = page["id"]
    bad = [
        [{"op": "explode"}],
        [{"op": "text_insert", "id": "bone001", "at": 0, "text": [{"t": "x"}]}],                   # no version
        [{"op": "text_insert", "id": "bone001", "v": 1, "at": 9, "text": [{"t": "x"}]}],           # out of range
        [{"op": "text_insert", "id": "bone001", "v": 1, "at": 0, "text": [{"t": "password: hunter2"}]}],
        [{"op": "set", "id": "bone001", "v": 1, "props": {"text": []}}],                           # structural via set
        [{"op": "set_type", "id": "bdiv0001", "v": 1, "type": "p"}],                               # not a text block
        [{"op": "insert", "block": para("bkid0001", "x"), "parent": "bdiv0001"}],                   # divider holds none
        [{"op": "insert", "block": para("bone001", "dup")}],                                        # duplicate id
        [{"op": "mark", "id": "bone001", "v": 1, "from": 0, "to": 1, "mark": "u", "on": True}],
        [],
    ]
    for ops in bad:
        with pytest.raises(CommandError):
            center.pages.apply(pid, {"ops": ops})
    assert center.pages.get(pid)["version"] == page["version"]


def test_nesting_has_a_depth_limit_and_a_block_cannot_move_into_itself(center):
    deep: dict = para("bdeep000", "0")
    node = deep
    for level in range(1, MAX_NEST + 1):
        child = para(f"bdeep{level:03d}", str(level))
        node["children"] = [child]
        node = child
    with pytest.raises(CommandError):
        validate_blocks([deep])
    page = center.pages.create({"blocks": [{**para("bouter01", "o"), "children": [para("binner01", "i")]}]})
    with pytest.raises(CommandError):
        center.pages.apply(page["id"], {"ops": [{"op": "move", "id": "bouter01", "v": 1, "parent": "binner01", "after": None}]})


def test_text_positions_count_a_mention_as_one(center):
    ref = {"kind": "page", "id": "pg_abcdefgh", "label": "Other"}
    spans = [{"t": "ab"}, {"ref": ref}, {"t": "cd", "b": True}]
    assert len(units(spans)) == 5 and spans_of(units(spans)) == spans
    assert normalize_spans([{"t": "a"}, {"t": "b"}, {"t": ""}]) == [{"t": "ab"}]
    tree, _, _ = apply_ops([{"id": "bone001", "type": "p", "v": 1, "text": spans}],
                           [{"op": "text_delete", "id": "bone001", "v": 1, "at": 1, "len": 2}])
    assert tree[0]["text"] == [{"t": "a"}, {"t": "cd", "b": True}]


# ---------------------------------------------------------------- property tests: diff and apply, round trips

WORDS = ["plan", "ship", "draft", "review", "notes", "launch", "zebra", "orbit", " ", "  "]
TEXT_TYPES = ["p", "h1", "h2", "h3", "bullet", "number", "quote", "todo", "callout"]


def random_spans(rng: random.Random) -> list[dict]:
    pieces = []
    for _ in range(rng.randint(0, 4)):
        if rng.random() < 0.1:
            pieces.append({"ref": {"kind": "page", "id": f"pg_{rng.randint(10**7, 10**8 - 1)}", "label": rng.choice(WORDS).strip() or "x"}})
            continue
        piece = {"t": rng.choice(WORDS) + rng.choice(WORDS)}
        for mark in ("b", "i", "c", "s"):
            if rng.random() < 0.2:
                piece[mark] = True
        pieces.append(piece)
    return normalize_spans(pieces)


class Ids:
    def __init__(self) -> None:
        self.n = 0

    def __call__(self) -> str:
        self.n += 1
        return f"bid{self.n:06d}"


def random_block(rng: random.Random, ids: Ids, depth: int) -> dict:
    if rng.random() < 0.12:
        return {"id": ids(), "type": "divider"}
    kind = rng.choice(TEXT_TYPES)
    block = {"id": ids(), "type": kind, "text": random_spans(rng)}
    if kind == "todo":
        block["checked"] = rng.random() < 0.5
    if kind == "callout":
        block["icon"] = rng.choice(["💡", "⚠️", "✅"])
    if depth < 3 and rng.random() < 0.25:
        block["children"] = [random_block(rng, ids, depth + 1) for _ in range(rng.randint(1, 3))]
    return block


def flat(tree: list[dict]) -> list[tuple[dict, list, list]]:
    out = []
    def go(blocks, owner):
        for b in blocks:
            out.append((b, blocks, owner))
            go(b.get("children") or [], b)
    go(tree, None)
    return out


def random_edit(rng: random.Random, tree: list[dict], ids: Ids) -> None:
    """One random change, made directly on the tree (as an editor or the AI would produce)."""
    nodes = flat(tree)
    choice = rng.random()
    if not nodes or choice < 0.2:
        target = rng.choice([tree] + [b.setdefault("children", []) for b, _, _ in nodes if b["type"] in TEXT_TYPES and depth_of(tree, b) < 3] or [tree])
        target.insert(rng.randint(0, len(target)), random_block(rng, ids, 3))
        return
    block, siblings, _ = rng.choice(nodes)
    if choice < 0.32:
        siblings.remove(block)
    elif choice < 0.45:
        siblings.remove(block)
        candidates = [tree] + [b.setdefault("children", []) for b, _, _ in flat(tree) if b["type"] in TEXT_TYPES and depth_of(tree, b) < 2]
        dest = rng.choice(candidates)
        dest.insert(rng.randint(0, len(dest)), block)
    elif block["type"] in TEXT_TYPES:
        if choice < 0.55:
            new_type = rng.choice(TEXT_TYPES)
            block["type"] = new_type
            block.pop("checked", None) if new_type != "todo" else block.setdefault("checked", False)
            block.pop("icon", None) if new_type != "callout" else block.setdefault("icon", "💡")
        elif choice < 0.85:
            u = units(block["text"])
            at = rng.randint(0, len(u))
            cut = rng.randint(0, min(4, len(u) - at))
            u[at:at + cut] = units(random_spans(rng))
            block["text"] = spans_of(u)
        elif block["type"] == "todo":
            block["checked"] = not block.get("checked")
        else:
            u = units(block["text"])
            if u:
                a = rng.randint(0, len(u) - 1)
                for i in range(a, min(len(u), a + 3)):
                    if u[i][0] == "t":
                        marks = set(u[i][2]) ^ {"b"}
                        u[i] = ("t", u[i][1], tuple(m for m in ("b", "i", "c", "s") if m in marks))
                block["text"] = spans_of(u)
    for b, _, _ in flat(tree):
        if "children" in b and not b["children"]:
            del b["children"]


def depth_of(tree: list[dict], target: dict) -> int:
    def go(blocks, d):
        for b in blocks:
            if b is target:
                return d
            found = go(b.get("children") or [], d + 1)
            if found is not None:
                return found
        return None
    return go(tree, 0) or 0


def versioned(tree: list[dict], v: int = 1) -> list[dict]:
    out = copy.deepcopy(tree)
    for b, _, _ in flat(out):
        b.setdefault("v", v)
    return out


def test_diff_then_apply_reaches_any_edited_page_and_the_reverse_diff_restores_it():
    """1,000 random edit sequences over random nested pages. Each one: the operations that turn the old page into the
    new one give exactly the new page, and the operations back (the foundation of undo, run 2b) give exactly the old."""
    rng = random.Random(762)
    for case in range(1_000):
        ids = Ids()
        old = versioned([random_block(rng, ids, 0) for _ in range(rng.randint(0, 8))])
        new = copy.deepcopy(old)
        for _ in range(rng.randint(1, 6)):
            random_edit(rng, new, ids)
        forward = diff_ops(old, new)
        if not forward:  # the edits cancelled out: nothing to send
            assert strip(old) == strip(new), f"case {case}: an empty diff means no change"
            continue
        reached, _, changed = apply_ops(old, forward)
        assert strip(reached) == strip(new), f"case {case}: forward"
        back = diff_ops(reached, strip(old))
        restored, _, _ = apply_ops(reached, back)
        assert strip(restored) == strip(old), f"case {case}: back"
        validate_blocks(reached)
        untouched = {b["id"] for b, _, _ in flat(old)} - changed
        assert all(b["v"] == 1 for b, _, _ in flat(reached) if b["id"] in untouched), "unchanged blocks keep their version"


def test_two_clients_interleaving_on_different_blocks_both_land_and_on_the_same_block_one_merges(center):
    page = center.pages.create({"blocks": [para("bone001", "alpha"), para("btwo001", "beta"), para("bthree01", "gamma")]})
    pid = page["id"]
    base = center.pages.get(pid)["blocks"]
    mine, theirs = copy.deepcopy(base), copy.deepcopy(base)
    mine[0]["text"] = [{"t": "alpha one"}]
    theirs[1]["text"] = [{"t": "beta two"}]
    theirs.append({"id": "bfour001", "type": "p", "text": [{"t": "delta"}]})
    center.pages.apply(pid, {"ops": diff_ops(base, theirs)})
    center.pages.apply(pid, {"ops": diff_ops(base, mine)})  # different blocks: no conflict, both kept
    now = strip(center.pages.get(pid)["blocks"])
    assert [b["text"][0]["t"] for b in now] == ["alpha one", "beta two", "gamma", "delta"]
    late = copy.deepcopy(base)
    late[0]["text"] = [{"t": "alpha late"}]
    with pytest.raises(OpConflict) as refused:
        center.pages.apply(pid, {"ops": diff_ops(base, late)})
    assert refused.value.block_id == "bone001"


# ---------------------------------------------------------------- the whole-page save, the API, the migration

def test_the_api_takes_operations_and_no_longer_takes_whole_pages(center):
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    http = TestClient(app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    page = center.pages.create({"blocks": [para("bone001", "A")]})
    pid = page["id"]
    refused = http.post(f"/v1/cc/pages/{pid}", json={"version": page["version"], "blocks": []}, headers=headers)
    assert refused.status_code == 400 and "operations" in refused.json()["detail"]["message"]
    ok = http.post(f"/v1/cc/pages/{pid}/ops", json={"ops": [{"op": "text_insert", "id": "bone001", "v": 1, "at": 1, "text": [{"t": "B"}]}]}, headers=headers)
    assert ok.status_code == 200 and ok.json()["blocks"][0]["text"] == [{"t": "AB"}]
    stale = http.post(f"/v1/cc/pages/{pid}/ops", json={"ops": [{"op": "delete", "id": "bone001", "v": 1}]}, headers=headers)
    assert stale.status_code == 409 and stale.json()["detail"]["code"] == "PAGE_CHANGED"
    renamed = http.post(f"/v1/cc/pages/{pid}", json={"version": ok.json()["version"], "title": "Still fine"}, headers=headers)
    assert renamed.status_code == 200 and renamed.json()["title"] == "Still fine"


def test_internal_whole_page_writes_go_through_operations(center):
    page = center.pages.create({"blocks": [para("bone001", "A"), para("btwo001", "B")]})
    new = copy.deepcopy(page["blocks"])
    new[1]["text"] = [{"t": "B changed"}]
    out = center.pages.update(page["id"], {"version": page["version"], "blocks": new}, actor="agent")
    assert [b["v"] for b in out["blocks"]] == [1, 2], "only the changed block gains a version"


def test_migration_4_gives_every_block_a_version_and_loses_nothing(tmp_path):
    path = tmp_path / "cc.db"
    c = CommandCenter(path, FakeContract(), lambda: [])
    pages = [c.pages.create({"template": t}) for t in ("weekly", "daily", "content", "blank")]
    nested = c.pages.create({"blocks": [{**para("bouter01", "o"), "children": [para("binner01", "i")]}]})
    before = {p["id"]: strip(c.pages.get(p["id"])["blocks"]) for p in [*pages, nested]}
    # Make the file look as it was before run 2a: no block versions, migration 4 not applied.
    db = c._db
    for pid in before:
        raw = json.loads(db.execute("SELECT blocks FROM page WHERE id = ?", (pid,)).fetchone()[0])
        for b, _, _ in flat(raw):
            b.pop("v", None)
        db.execute("UPDATE page SET blocks = ? WHERE id = ?", (json.dumps(raw), pid))
    db.execute("DELETE FROM cc_schema_migration WHERE id = 4")
    db.execute("PRAGMA user_version = 3")
    db.commit()
    c.stop()
    again = CommandCenter(path, FakeContract(), lambda: [])
    try:
        assert again.schema["version"] == len(MIGRATIONS) == 4 and 4 in again.schema["ran"]
        for pid, blocks in before.items():
            after = again.pages.get(pid)["blocks"]
            assert strip(after) == blocks, pid
            assert all(b["v"] == 1 for b, _, _ in flat(after))
    finally:
        again.stop()


def test_a_page_the_migration_cannot_read_stops_it_and_changes_nothing(tmp_path):
    path = tmp_path / "cc.db"
    c = CommandCenter(path, FakeContract(), lambda: [])
    page = c.pages.create({"blocks": [para("bone001", "A")]})
    c._db.execute("UPDATE page SET blocks = ? WHERE id = ?", (json.dumps(["not a block"]), page["id"]))
    c._db.execute("DELETE FROM cc_schema_migration WHERE id = 4")
    c._db.execute("PRAGMA user_version = 3")
    c._db.commit()
    c.stop()
    with pytest.raises(Exception):
        CommandCenter(path, FakeContract(), lambda: [])
    raw = sqlite3.connect(path)
    try:
        assert raw.execute("PRAGMA user_version").fetchone()[0] == 3
        assert json.loads(raw.execute("SELECT blocks FROM page").fetchone()[0]) == ["not a block"]
    finally:
        raw.close()


PARITY = Path(__file__).resolve().parents[2] / "glass/tests/fixtures/pageops-parity.json"


def test_the_shared_cases_glass_also_checks_apply_the_same_here():
    """`apps/glass/tests/pageops.test.mjs` applies the same operations with Glass's mirror; both must reach `new`."""
    cases = json.loads(PARITY.read_text(encoding="utf-8"))["cases"]
    assert len(cases) == 80
    for i, case in enumerate(cases):
        assert strip(apply_ops(case["old"], case["ops"])[0]) == case["new"], i


def test_a_thousand_edits_in_a_row_then_one_batch_back_restores_the_page_exactly(center):
    """The plan's acceptance in miniature for run 2a: 1,000 random edits, each saved as its own batch through the store
    (versions checked every time), then the reverse operations, as one batch, give back the original page exactly."""
    rng = random.Random(7_620)
    ids = Ids()
    original = [random_block(rng, ids, 0) for _ in range(12)]
    page = center.pages.create({"blocks": original})
    pid = page["id"]
    start = strip(center.pages.get(pid)["blocks"])
    for _ in range(1_000):
        current = center.pages.get(pid)["blocks"]
        edited = strip(current)
        random_edit(rng, edited, ids)
        ops = diff_ops(current, edited)
        if ops:
            center.pages.apply(pid, {"ops": ops})
            assert strip(center.pages.get(pid)["blocks"]) == strip(edited)
    final = center.pages.get(pid)["blocks"]
    center.pages.apply(pid, {"ops": diff_ops(final, start)})
    assert strip(center.pages.get(pid)["blocks"]) == start
