# Glass interaction benchmark (plan 76, stage 0, run 0b)

Machine: Linux x86_64; headless Chromium; viewport 1440×900. Gateway on loopback with synthetic data: 300 pages, a 1,000-block page, a 50-block page, a table of 10,000 rows.

Times are taken in the page with `performance.now()`. Waiting for the screen polls once per frame, so each time can be up to about 16 ms late. Compare runs on the same machine only.

| Interaction | Budget | Measured | Result |
|---|---:|---:|---|
| Keystroke to screen, 50-block page (p95) | ≤ 16.7 ms | 15.7 ms | PASS |
| Keystroke to screen, 1,000-block page (p95) | ≤ 16.7 ms | 17.9 ms | FAIL |
| Open a page seen this session (median) | ≤ 150 ms | 86.6 ms | PASS |
| Open a cold page (median) | ≤ 400 ms | 139 ms | PASS |
| Database view of 10,000 rows: first rows | ≤ 300 ms | 1754 ms | FAIL |
| Database view of 10,000 rows: scrolling (60 fps, 57 counts) | ≥ 57 fps | 25 fps | FAIL |
| Cell edit shown | ≤ 16 ms | 2422 ms | FAIL |
| Cell edit confirmed | ≤ 1000 ms | 2422 ms | FAIL |
| Change made elsewhere appears | ≤ 500 ms | 364 ms | PASS |
| Ctrl/Cmd+K palette: a page by its title | ≤ 100 ms | 42.8 ms | PASS |
| Search (workspace find): title results | ≤ 100 ms | 30.0 ms | PASS |
| Search (workspace find): full-text results | ≤ 300 ms | 34.6 ms | PASS |
| Undo a block split | ≤ 50 ms | — | NOT SUPPORTED |

**7 of 13 budgets met.**

## Details

- Keystroke p50: 50-block page 9.5 ms; 1,000-block page 11.3 ms (80 keystrokes).
- Cold open of the 1,000-block page: 163 ms.
- Table: first rows 1754 ms; all 10,000 rows drawn after 1792 ms; 49 frames over 25 ms while scrolling.
- Cell edit: shown only after the server answered and the view was redrawn (optimistic: False).
- Ctrl/Cmd+K opens: cyber-palette. Live change samples (ms): 393, 355, 312, 392, 364.
- Undo: block counts before split, after split, after Ctrl/Cmd+Z = [50, 51, 51].

## Raw results

```json
{
 "cell_confirmed": 2421.5,
 "cell_optimistic": false,
 "cell_shown": 2421.5,
 "ctrl_k_opens": "cyber-palette",
 "find_text": 34.60000000009313,
 "find_title": 30.0,
 "keystroke_big_p50": 11.300000000046566,
 "keystroke_big_p95": 17.914999999909195,
 "keystroke_samples": 80,
 "keystroke_small_p50": 9.5,
 "keystroke_small_p95": 15.670000000088471,
 "live_change": 364.18540299996494,
 "live_change_samples": [
  393.3707600001526,
  354.87230300009287,
  312.3166620000575,
  391.7968809998911,
  364.18540299996494
 ],
 "open_cold": 139.30000000004657,
 "open_cold_big": 163.30000000004657,
 "open_cold_samples": [
  172.69999999995343,
  106.30000000004657,
  139.30000000004657
 ],
 "open_warm": 86.5999999998603,
 "open_warm_samples": [
  94.89999999990687,
  117.20000000018626,
  127.80000000004657,
  63.5,
  84.0999999998603,
  77.70000000018626,
  102.0,
  65.5,
  53.0,
  86.69999999995343,
  114.0,
  86.5999999998603,
  74.89999999990687,
  65.30000000004657,
  106.0999999998603
 ],
 "palette_title": 42.800000000046566,
 "table_all_rows": 1792.3000000000466,
 "table_first_rows": 1753.5,
 "table_long_frames": 49,
 "table_rendered_rows": 10000,
 "table_scroll_fps": 25.47707063642603,
 "undo": null,
 "undo_blocks": [
  50,
  51,
  51
 ]
}
```
