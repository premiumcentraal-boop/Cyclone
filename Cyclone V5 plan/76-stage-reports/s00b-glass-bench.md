# Glass interaction benchmark (plan 76, stage 0, run 0b)

Machine: Linux x86_64; headless Chromium; viewport 1440×900. Gateway on loopback with synthetic data: 300 pages, a 1,000-block page, a 50-block page, a table of 10,000 rows.

Times are taken in the page with `performance.now()`. Waiting for the screen polls once per frame, so each time can be up to about 16 ms late. Compare runs on the same machine only.

| Interaction | Budget | Measured | Result |
|---|---:|---:|---|
| Keystroke to screen, 50-block page (p95) | ≤ 16.7 ms | 15.6 ms | PASS |
| Keystroke to screen, 1,000-block page (p95) | ≤ 16.7 ms | 16.3 ms | PASS |
| Open a page seen this session (median) | ≤ 150 ms | 54.0 ms | PASS |
| Open a cold page (median) | ≤ 400 ms | 97.2 ms | PASS |
| Database view of 10,000 rows: first rows | ≤ 300 ms | 1269 ms | FAIL |
| Database view of 10,000 rows: scrolling (60 fps, 57 counts) | ≥ 57 fps | 32 fps | FAIL |
| Cell edit shown | ≤ 16 ms | 1777 ms | FAIL |
| Cell edit confirmed | ≤ 1000 ms | 1777 ms | FAIL |
| Change made elsewhere appears | ≤ 500 ms | 2275 ms | FAIL |
| Ctrl/Cmd+K palette: a page by its title | ≤ 100 ms | 22.4 ms | PASS |
| Search (workspace find): title results | ≤ 100 ms | 29.4 ms | PASS |
| Search (workspace find): full-text results | ≤ 300 ms | 22.4 ms | PASS |
| Undo a block split | ≤ 50 ms | — | NOT SUPPORTED |

**7 of 13 budgets met.**

## Details

- Keystroke p50: 50-block page 9.4 ms; 1,000-block page 9.5 ms (80 keystrokes).
- Cold open of the 1,000-block page: 132 ms.
- Table: first rows 1269 ms; all 10,000 rows drawn after 1311 ms; 52 frames over 25 ms while scrolling.
- Cell edit: shown only after the server answered and the view was redrawn (optimistic: False).
- Ctrl/Cmd+K opens: cyber-palette. Live change samples (ms): 1949, 3791, 2596, 2275, 1868.
- Undo: block counts before split, after split, after Ctrl/Cmd+Z = [50, 51, 51].

## Raw results

```json
{
 "cell_confirmed": 1776.8000000000466,
 "cell_optimistic": false,
 "cell_shown": 1776.8000000000466,
 "ctrl_k_opens": "cyber-palette",
 "find_text": 22.399999999965075,
 "find_title": 29.400000000023283,
 "keystroke_big_p50": 9.549999999988358,
 "keystroke_big_p95": 16.324999999988357,
 "keystroke_samples": 80,
 "keystroke_small_p50": 9.35000000000582,
 "keystroke_small_p95": 15.604999999975552,
 "live_change": 2275.398445999997,
 "live_change_samples": [
  1948.9294819999827,
  3791.404854999996,
  2595.856699999956,
  2275.398445999997,
  1868.1701750000457
 ],
 "open_cold": 97.20000000001164,
 "open_cold_big": 132.10000000003492,
 "open_cold_samples": [
  121.5,
  97.20000000001164,
  36.100000000034925
 ],
 "open_warm": 54.0,
 "open_warm_samples": [
  49.5,
  29.0,
  52.79999999998836,
  56.20000000001164,
  48.899999999965075,
  58.399999999965075,
  54.0,
  63.0,
  53.5,
  59.5,
  71.79999999998836,
  26.70000000001164,
  62.5,
  49.79999999998836,
  62.899999999965075
 ],
 "palette_title": 22.399999999965075,
 "table_all_rows": 1311.0999999999767,
 "table_first_rows": 1269.2999999999884,
 "table_long_frames": 52,
 "table_rendered_rows": 10000,
 "table_scroll_fps": 32.30776877578446,
 "undo": null,
 "undo_blocks": [
  50,
  51,
  51
 ]
}
```
