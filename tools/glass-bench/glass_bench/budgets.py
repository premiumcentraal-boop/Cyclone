"""Plan 76 §5.2 speed budgets, in milliseconds (fps for scrolling). The benchmark grades every run against these."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Budget:
    key: str
    label: str
    limit: float
    unit: str = "ms"
    higher_is_better: bool = False

    def grade(self, value: float | None) -> str:
        if value is None:
            return "NOT SUPPORTED"
        ok = value >= self.limit if self.higher_is_better else value <= self.limit
        return "PASS" if ok else "FAIL"


BUDGETS: tuple[Budget, ...] = (
    # One frame at 60 Hz. The benchmark times a keystroke until the next frame starts, so any keystroke handled within
    # its frame reads 0-16.7 ms; a slower one reads more.
    Budget("keystroke_small_p95", "Keystroke to screen, 50-block page (p95)", 16.7),
    Budget("keystroke_big_p95", "Keystroke to screen, 1,000-block page (p95)", 16.7),
    Budget("open_warm", "Open a page seen this session (median)", 150),
    Budget("open_cold", "Open a cold page (median)", 400),
    Budget("table_first_rows", "Database view of 10,000 rows: first rows", 300),
    # 60 fps is the display's ceiling, so 57 (95 % of it) passes; long frames are reported beside it.
    Budget("table_scroll_fps", "Database view of 10,000 rows: scrolling (60 fps, 57 counts)", 57, unit="fps", higher_is_better=True),
    Budget("cell_shown", "Cell edit shown", 16),
    Budget("cell_confirmed", "Cell edit confirmed", 1000),
    Budget("live_change", "Change made elsewhere appears", 500),
    Budget("palette_title", "Ctrl/Cmd+K palette: a page by its title", 100),
    Budget("find_title", "Search (workspace find): title results", 100),
    Budget("find_text", "Search (workspace find): full-text results", 300),
    Budget("undo", "Undo a block split", 50),
)
BY_KEY = {b.key: b for b in BUDGETS}


def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("no samples")
    k = (len(ordered) - 1) * q
    low, high = int(k), min(int(k) + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (k - low)


def median(values: list[float]) -> float:
    return percentile(values, 0.5)
