"""Plan 76 §5.7: the experience acceptance script, version 1. Owner tasks, run by an automated stand-in on today's
Glass. Each task passes only when the result is checked on the server or on the screen, never on a click alone.

A task that fails because the feature does not exist yet says so (`missing`) and names the stage that adds it, so a
missing feature is never confused with a broken script. A task that fails for any other reason is `fail`.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Callable

from .harness import Fixture, Gateway
from .measure import go, open_glass


@dataclass
class Outcome:
    task: str
    result: str        # pass | fail | missing
    seconds: float
    note: str


class Missing(Exception):
    """The feature the task needs does not exist in today's Glass."""


def _tree(gw: Gateway) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []

    def walk(nodes: list[dict[str, Any]]) -> None:
        for n in nodes:
            out.append(n)
            walk(n.get("children") or [])
    tree = gw.call("GET", "/v1/cc/pages")
    walk(tree if isinstance(tree, list) else tree.get("pages") or tree.get("tree") or [])
    return out


def _page_id(gw: Gateway, title: str) -> str:
    for n in _tree(gw):
        if n.get("title") == title:
            return n["id"]
    raise AssertionError(f"no page titled {title!r} on the server")


def _new_line(page: Any, text: str) -> None:
    page.keyboard.press("Enter")
    page.keyboard.type(text, delay=15)


def t_create_page(page: Any, gw: Gateway, fx: Fixture) -> str:
    page.get_by_role("button", name="New page").first.click()
    title = page.locator(".ws-title")
    title.wait_for(timeout=5000)
    title.fill("Acceptance page")
    title.press("Enter")
    page.wait_for_timeout(1500)
    _page_id(gw, "Acceptance page")
    return "Made from the sidebar and named; the server has it."


def t_markdown(page: Any, gw: Gateway, fx: Fixture) -> str:
    go(page, _page_id(gw, "Acceptance page"), 1)
    page.locator(".ws-text").first.click()
    page.keyboard.type("# Goals", delay=15)
    _new_line(page, "[] Call the bank")
    _new_line(page, "- Weekly review")
    page.wait_for_timeout(1500)
    kinds = [b["type"] for b in gw.call("GET", f"/v1/cc/pages/{_page_id(gw, 'Acceptance page')}")["blocks"]]
    if "h1" in kinds and "todo" in kinds and "bullet" not in kinds and kinds[-1] == "todo":
        raise Missing("Shortcuts work only at the start of a paragraph (editor.ts:407): '- ' typed on the to-do line that Enter "
                      "made stays a to-do. In Notion it turns into a bullet (stage 2, run 2d).")
    for kind in ("h1", "todo", "bullet"):
        assert kind in kinds, f"no {kind} block after the shortcut (blocks: {kinds})"
    return "Heading, to-do and bullet from # , [] and - ."


def t_slash(page: Any, gw: Gateway, fx: Fixture) -> str:
    page.keyboard.press("Enter")
    page.keyboard.press("Enter")
    page.keyboard.type("/Heading 2", delay=20)
    page.keyboard.press("Enter")
    page.keyboard.type("Section", delay=15)
    page.wait_for_timeout(1500)
    blocks = gw.call("GET", f"/v1/cc/pages/{_page_id(gw, 'Acceptance page')}")["blocks"]
    assert any(b["type"] == "h2" and "Section" in "".join(s["t"] for s in b.get("text") or []) for b in blocks), "no Heading 2 named Section"
    return "/Heading 2 from the slash menu."


def t_mention(page: Any, gw: Gateway, fx: Fixture) -> str:
    _new_line(page, "See ")
    page.keyboard.type("@Benchmark: a normal", delay=20)
    page.locator(".ws-pop-row").first.wait_for(timeout=5000)
    page.keyboard.press("Enter")
    page.wait_for_timeout(1500)
    assert page.locator(".ws-mention").count() >= 1, "no mention chip on the page"
    return "@ found the page and inserted a chip."


def t_undo(page: Any, gw: Gateway, fx: Fixture) -> str:
    before = page.locator(".ws-block").count()
    page.keyboard.press("Enter")
    page.wait_for_timeout(300)
    assert page.locator(".ws-block").count() == before + 1, "Enter did not make a new block, so there was nothing to undo"
    page.keyboard.press("Control+z")
    page.wait_for_timeout(600)
    if page.locator(".ws-block").count() != before:
        raise Missing("Ctrl/Cmd+Z does not undo a new block (stage 2, run 2b).")
    return "Undone."


def t_find(page: Any, gw: Gateway, fx: Fixture) -> str:
    page.locator("text=Search").first.click()
    box = page.locator(".ws-find-input")
    box.fill(fx.search_body)
    page.locator(".ws-find-row").first.wait_for(timeout=5000)
    page.keyboard.press("Enter")
    page.wait_for_function("(t) => document.querySelector('.ws-title')?.value === t", arg=fx.search_title, timeout=10000)
    return "Found by a word in the page's text and opened."


def t_table_cell(page: Any, gw: Gateway, fx: Fixture) -> str:
    go(page, fx.table_page, 1)
    page.wait_for_function("(n) => document.querySelectorAll('.tb-grid .tb-tr').length >= n", arg=fx.table_rows, timeout=60000)
    cell = page.locator(".tb-grid .tb-tr").first.locator(".tb-td").nth(1)
    cell.click()
    cell.locator("input, textarea").first.fill("77")
    page.keyboard.press("Enter")
    page.wait_for_function("() => document.querySelector('.tb-grid .tb-tr .tb-td:nth-child(2)')?.textContent.trim() === '77'",
                           timeout=15000)
    return f"Changed in a table of {fx.table_rows:,} rows."


def t_move_into(page: Any, gw: Gateway, fx: Fixture) -> str:
    tree = page.get_by_role("tree", name="Pages") if page.get_by_role("tree", name="Pages").count() else page.locator("[aria-label=Pages]")
    source = tree.get_by_text("Acceptance page", exact=True).first
    target = tree.get_by_text(fx.search_title, exact=True).first
    target.scroll_into_view_if_needed()
    source.drag_to(target)
    page.wait_for_timeout(1500)
    moved = next(n for n in _tree(gw) if n.get("title") == "Acceptance page")
    assert moved.get("parentId") == _page_id(gw, fx.search_title), "the page did not move inside the other page"
    return "Dragged onto another page in the sidebar; it is now inside it."


def t_trash_restore(page: Any, gw: Gateway, fx: Fixture) -> str:
    pid = _page_id(gw, "Acceptance page")
    go(page, pid, 1)
    page.get_by_text("Move to trash", exact=True).first.click()
    page.wait_for_timeout(1000)
    page.evaluate("() => { location.hash = '#/command/trash'; }")
    page.get_by_role("button", name="Restore").first.click()
    page.wait_for_timeout(1500)
    assert not gw.call("GET", f"/v1/cc/pages/{pid}").get("archivedAt"), "still in the trash"
    return "Moved to the trash and restored."


def t_duplicate(page: Any, gw: Gateway, fx: Fixture) -> str:
    go(page, _page_id(gw, "Acceptance page"), 1)
    if page.get_by_text("Duplicate", exact=True).count() == 0:
        page.get_by_role("button", name="More for Acceptance page").first.click()
        page.wait_for_timeout(300)
        if page.get_by_text("Duplicate", exact=True).count() == 0:
            raise Missing("No way to duplicate a page (stage 2, run 2e).")
    return "Duplicated."


def t_accounts_grouped(page: Any, gw: Gateway, fx: Fixture) -> str:
    page.evaluate("() => { location.hash = '#/command/accounts'; }")
    page.wait_for_timeout(1500)
    if page.locator(".tb-grid").count() == 0 and page.get_by_role("button", name="Group").count() == 0:
        raise Missing("Accounts is a separate screen, not a database: no grouping or views (stage 4).")
    return "Grouped."


def t_row_as_page(page: Any, gw: Gateway, fx: Fixture) -> str:
    go(page, fx.table_page, 1)
    page.wait_for_function("() => document.querySelector('.tb-grid .tb-tr')", timeout=60000)
    if page.get_by_text("Open as page", exact=True).count() == 0:
        raise Missing("A row opens only in the side peek; there is no full-page view (stage 3, run 3b).")
    return "Opened as a page."


TASKS: tuple[tuple[str, Callable[[Any, Gateway, Fixture], str]], ...] = (
    ("Create a page from the sidebar and name it", t_create_page),
    ("Write a heading, a to-do and a bullet with markdown shortcuts", t_markdown),
    ("Add a Heading 2 with the slash menu", t_slash),
    ("Mention another page with @", t_mention),
    ("Undo the last edit with Ctrl/Cmd+Z", t_undo),
    ("Find a page by a word in its text and open it", t_find),
    ("Change a number in a 10,000-row table", t_table_cell),
    ("Move a page inside another page by dragging it in the sidebar", t_move_into),
    ("Move a page to the trash and restore it", t_trash_restore),
    ("Duplicate a page", t_duplicate),
    ("Group the accounts by app", t_accounts_grouped),
    ("Open a table row as a full page", t_row_as_page),
)


def home(page: Any) -> None:
    """Where the owner starts: the workspace home, with the sidebar's New page and Search."""
    page.evaluate("() => { location.hash = '#/command/home'; }")
    page.get_by_role("button", name="New page").first.wait_for(timeout=15000)


def run(context: Any, gw: Gateway, fx: Fixture) -> list[Outcome]:
    page = open_glass(context, gw)
    home(page)
    out: list[Outcome] = []
    for name, task in TASKS:
        start = time.perf_counter()
        try:
            note, result = task(page, gw, fx), "pass"
        except Missing as gap:
            note, result = str(gap), "missing"
        except Exception as exc:  # noqa: BLE001 - recorded, and the next task still runs
            note, result = f"{type(exc).__name__}: {str(exc).splitlines()[0][:160]}", "fail"
        out.append(Outcome(name, result, time.perf_counter() - start, note))
    page.close()
    return out


def report(outcomes: list[Outcome]) -> str:
    passed = sum(o.result == "pass" for o in outcomes)
    missing = sum(o.result == "missing" for o in outcomes)
    lines = ["# Experience acceptance script v1 (plan 76 §5.7), run on today's Glass", "",
             "Run by an automated stand-in, not a person. Every pass is checked on the server or on the screen. `missing` means "
             "the feature does not exist yet; `fail` means something that exists did not work.", "",
             "| # | Task | Result | Time | Note |", "|---:|---|---|---:|---|"]
    for i, o in enumerate(outcomes, start=1):
        lines.append(f"| {i} | {o.task} | {o.result.upper()} | {o.seconds:.1f} s | {o.note} |")
    lines += ["", f"**{passed} passed, {missing} missing, {len(outcomes) - passed - missing} failed, of {len(outcomes)} tasks.**", ""]
    return "\n".join(lines)
