"""Measure today's Glass against the plan 76 §5.2 budgets, in a real browser on a real gateway.

Timings are taken inside the page with performance.now(). Waiting for a DOM condition polls once per animation frame,
so a measured time can be up to one frame (about 16 ms) late; each figure is a slight overestimate, never an
underestimate.
"""
from __future__ import annotations

import time
from typing import Any

from .budgets import median, percentile
from .harness import Fixture, Gateway

FRAME_POLL = "raf"
INSTALL_KEYS = """() => {
  window.__ks = [];
  if (window.__keysInstalled) return;
  window.__keysInstalled = true;
  document.addEventListener('keydown', () => { window.__kd = performance.now(); }, true);
  document.addEventListener('input', () => {
    const t0 = window.__kd;
    requestAnimationFrame(() => window.__ks.push(performance.now() - t0));
  }, true);
}"""


def open_glass(context: Any, gw: Gateway) -> Any:
    page = context.new_page()
    page.goto(gw.launch_url())
    page.wait_for_selector(".ws-title, .ws-editor, nav, main", timeout=20000)
    dismiss_welcome(page)
    return page


def dismiss_welcome(page: Any) -> None:
    button = page.get_by_role("button", name="Got it")
    try:
        button.first.click(timeout=1500)
    except Exception:  # noqa: BLE001 - not shown this time
        pass


def go(page: Any, page_id: str, expect_blocks: int) -> float:
    """Navigate to a page by its hash route and return ms until its blocks are drawn."""
    page.evaluate("(id) => { window.__t0 = performance.now(); location.hash = '#/command/page/' + id; }", page_id)
    page.wait_for_function("(n) => document.querySelectorAll('.ws-block').length >= n && "
                           "location.hash.endsWith(window.__lastId || '')", arg=expect_blocks, polling=FRAME_POLL, timeout=30000)
    took = page.evaluate("() => performance.now() - window.__t0")
    return float(took)


def go_home(page: Any) -> None:
    page.evaluate("() => { location.hash = '#/command/home'; }")
    page.wait_for_function("() => document.querySelectorAll('.ws-block').length === 0", polling=FRAME_POLL, timeout=15000)


def keystrokes(page: Any, page_id: str, blocks: int, count: int = 40) -> list[float]:
    go(page, page_id, blocks)
    page.evaluate(INSTALL_KEYS)
    target = page.locator(".ws-text").nth(min(blocks // 2, blocks - 1))
    target.scroll_into_view_if_needed()
    target.click()
    page.keyboard.press("End")
    page.keyboard.type("x" * count, delay=40)
    page.wait_for_timeout(300)
    return [float(v) for v in page.evaluate("() => window.__ks")]


def page_opens(context: Any, gw: Gateway, fx: Fixture) -> dict[str, Any]:
    cold, warm = [], []
    for _ in range(3):
        page = open_glass(context, gw)
        cold.append(go(page, fx.small_page, 50))
        for _ in range(5):
            go_home(page)
            warm.append(go(page, fx.small_page, 50))
        page.close()
    big_page = open_glass(context, gw)
    big_cold = go(big_page, fx.big_page, 1000)
    big_page.close()
    return {"open_cold": median(cold), "open_warm": median(warm), "open_cold_samples": cold, "open_warm_samples": warm,
            "open_cold_big": big_cold}


def table(page: Any, fx: Fixture) -> dict[str, Any]:
    page.evaluate("(id) => { window.__t0 = performance.now(); location.hash = '#/command/page/' + id; }", fx.table_page)
    page.wait_for_function("() => document.querySelector('.tb-grid .tb-tr')", polling=FRAME_POLL, timeout=60000)
    first = float(page.evaluate("() => performance.now() - window.__t0"))
    page.wait_for_function("(n) => document.querySelectorAll('.tb-grid .tb-tr').length >= n", arg=fx.table_rows,
                           polling=FRAME_POLL, timeout=120000)
    all_rows = float(page.evaluate("() => performance.now() - window.__t0"))
    rendered = int(page.evaluate("() => document.querySelectorAll('.tb-grid .tb-tr').length"))
    scroll = page.evaluate("""() => new Promise(resolve => {
      const grid = document.querySelector('.tb-grid');
      let el = grid.parentElement;
      while (el && !(el.scrollHeight > el.clientHeight && /(auto|scroll)/.test(getComputedStyle(el).overflowY))) el = el.parentElement;
      const box = el || document.scrollingElement;
      const start = performance.now(); let frames = 0, long = 0, last = start;
      function step(now) {
        frames += 1; if (now - last > 25) long += 1; last = now;
        box.scrollTop += 60;
        if (now - start < 2000) requestAnimationFrame(step);
        else resolve({ fps: frames / ((now - start) / 1000), long, frames });
      }
      requestAnimationFrame(step);
    })""")
    return {"table_first_rows": first, "table_all_rows": all_rows, "table_rendered_rows": rendered,
            "table_scroll_fps": float(scroll["fps"]), "table_long_frames": int(scroll["long"])}


def cell_edit(page: Any) -> dict[str, Any]:
    """Edit the Score cell of the first row: ms until the new value is on screen, and until the server has it."""
    page.evaluate("() => document.scrollingElement.scrollTop = 0")
    row = page.locator(".tb-grid .tb-tr").first
    cell = row.locator(".tb-td").nth(1)
    cell.click()
    editor = cell.locator("input, textarea").first
    editor.wait_for(timeout=5000)
    editor.fill("4242")
    page.evaluate("""() => document.addEventListener('keydown', e => { if (e.key === 'Enter') window.__t0 = performance.now(); },
                     { capture: true, once: true })""")
    page.keyboard.press("Enter")
    page.wait_for_function("() => [...document.querySelectorAll('.tb-grid .tb-tr:first-child .tb-td')]"
                           ".some(td => td.textContent.includes('4242') && !td.querySelector('input'))",
                           polling=FRAME_POLL, timeout=30000)
    shown = float(page.evaluate("() => performance.now() - window.__t0"))
    # Today the cell only shows the value after the server answers and the view is drawn again, so shown == confirmed.
    return {"cell_shown": shown, "cell_confirmed": shown, "cell_optimistic": False}


def _length(spans: list[dict[str, Any]]) -> int:
    """A block's text length as operations count it: characters, and one per mention."""
    return sum(1 if "ref" in s else len(s.get("t", "")) for s in spans)


def live_change(context: Any, gw: Gateway, fx: Fixture, samples: int = 5) -> dict[str, Any]:
    """A change made elsewhere (another tab, the AI, a phone): the page is changed through the API while a fresh tab with
    no edits of its own shows it. Samples start at spread-out moments, so they fall across any polling cycle."""
    import random

    rng = random.Random(76)
    page = open_glass(context, gw)
    go(page, fx.big_page, 1000)
    took = []
    for n in range(samples):
        page.wait_for_timeout(int(rng.uniform(500, 4500)))
        current = gw.call("GET", f"/v1/cc/pages/{fx.big_page}")
        marker = f"livechange marker {n}"
        last = current["blocks"][-1]
        # Plan 76 run 2a: a page changes through operations: replace the last block's words with the marker.
        ops = [{"op": "text_delete", "id": last["id"], "v": last["v"], "at": 0, "len": _length(last["text"])}] if _length(last["text"]) else []
        ops.append({"op": "text_insert", "id": last["id"], "v": last["v"], "at": 0, "text": [{"t": marker}]})
        start = time.perf_counter()
        gw.call("POST", f"/v1/cc/pages/{fx.big_page}/ops", {"ops": ops})
        page.wait_for_function("(m) => document.body.innerText.includes(m)", arg=marker, polling=FRAME_POLL, timeout=60000)
        took.append((time.perf_counter() - start) * 1000)
    page.close()
    return {"live_change": median(took), "live_change_samples": took}


def palette(page: Any, fx: Fixture) -> dict[str, Any]:
    """Ctrl/Cmd+K as the owner meets it: today it opens Cyber's palette. Time until the page is listed by its title,
    or None when the palette does not list pages by title."""
    go(page, fx.small_page, 50)
    page.locator(".ws-title").click()
    page.keyboard.press("Control+k")
    box = page.locator(".cyber-palette-input, .ws-find-input").first
    box.wait_for(timeout=5000)
    opens = "cyber-palette" if page.locator(".cyber-palette-input").count() else "workspace-find"
    page.evaluate("() => { window.__t0 = performance.now(); }")
    box.fill("Benchmark: a normal")
    try:
        page.wait_for_function("() => [...document.querySelectorAll('.cyber-palette-list, .ws-find-results')]"
                               ".some(l => l.textContent.includes('Benchmark: a normal page'))", polling=FRAME_POLL, timeout=3000)
        took: float | None = float(page.evaluate("() => performance.now() - window.__t0"))
    except Exception:  # noqa: BLE001 - not listed
        took = None
    page.keyboard.press("Escape")
    return {"palette_title": took, "ctrl_k_opens": opens}


def quick_find(page: Any, fx: Fixture) -> dict[str, Any]:
    """The workspace's own find, opened from the sidebar's Search."""
    go(page, fx.small_page, 50)
    page.locator("text=Search").first.click()
    found = page.locator(".ws-find-input")
    found.wait_for(timeout=5000)
    results: dict[str, Any] = {}
    for key, query, expect in (("find_title", "Benchmark: a normal", "Benchmark: a normal page"),
                               ("find_text", fx.search_body, "Benchmark: a normal page")):
        found.fill("")
        page.evaluate("() => { window.__t0 = performance.now(); }")
        found.fill(query)
        page.wait_for_function("(t) => [...document.querySelectorAll('.ws-find-row')].some(r => r.textContent.includes(t))",
                               arg=expect, polling=FRAME_POLL, timeout=15000)
        results[key] = float(page.evaluate("() => performance.now() - window.__t0"))
    page.keyboard.press("Escape")
    return results


def undo(page: Any, fx: Fixture) -> dict[str, Any]:
    go(page, fx.small_page, 50)
    before = page.locator(".ws-block").count()
    target = page.locator(".ws-text").nth(5)
    target.click()
    page.keyboard.press("End")
    page.keyboard.press("Enter")
    page.wait_for_timeout(300)
    split = page.locator(".ws-block").count()
    page.evaluate("""() => document.addEventListener('keydown', e => { if (e.key === 'z') window.__t0 = performance.now(); },
                     { capture: true, once: true })""")
    page.keyboard.press("Control+z")
    page.wait_for_timeout(600)
    after = page.locator(".ws-block").count()
    restored = split == before + 1 and after == before
    return {"undo": float(page.evaluate("() => performance.now() - window.__t0")) if restored else None,
            "undo_blocks": [before, split, after]}


def run_all(context: Any, gw: Gateway, fx: Fixture) -> dict[str, Any]:
    out: dict[str, Any] = {}
    out.update(page_opens(context, gw, fx))
    page = open_glass(context, gw)
    small = keystrokes(page, fx.small_page, 50)
    big = keystrokes(page, fx.big_page, 1000)
    out.update({"keystroke_small_p95": percentile(small, 0.95), "keystroke_small_p50": median(small),
                "keystroke_big_p95": percentile(big, 0.95), "keystroke_big_p50": median(big),
                "keystroke_samples": len(small) + len(big)})
    out.update(palette(page, fx))
    out.update(quick_find(page, fx))
    out.update(undo(page, fx))
    page.close()
    out.update(live_change(context, gw, fx))
    page = open_glass(context, gw)
    out.update(table(page, fx))
    out.update(cell_edit(page))
    page.close()
    return out
