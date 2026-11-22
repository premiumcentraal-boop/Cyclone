# Glass interaction benchmark (plan 76, stage 0, run 0b)

Machine: Linux x86_64; headless Chromium; viewport 1440×900. Gateway on loopback with synthetic data: 300 pages, a 1,000-block page, a 50-block page, a table of 10,000 rows.

Times are taken in the page with `performance.now()`. Waiting for the screen polls once per frame, so each time can be up to about 16 ms late. Compare runs on the same machine only.

| Interaction | Budget | Measured | Result |
|---|---:|---:|---|
| Keystroke to screen, 50-block page (p95) | ≤ 16.7 ms | 15.7 ms | PASS |
| Keystroke to screen, 1,000-block page (p95) | ≤ 16.7 ms | 17.6 ms | FAIL |
| Open a page seen this session (median) | ≤ 150 ms | 76.3 ms | PASS |
| Open a cold page (median) | ≤ 400 ms | 156 ms | PASS |
| Database view of 10,000 rows: first rows | ≤ 300 ms | 1731 ms | FAIL |
| Database view of 10,000 rows: scrolling (60 fps, 57 counts) | ≥ 57 fps | 25 fps | FAIL |
| Cell edit shown | ≤ 16 ms | 3061 ms | FAIL |
| Cell edit confirmed | ≤ 1000 ms | 3061 ms | FAIL |
| Change made elsewhere appears | ≤ 500 ms | 296 ms | PASS |
| Ctrl/Cmd+K palette: a page by its title | ≤ 100 ms | 32.8 ms | PASS |
| Search (workspace find): title results | ≤ 100 ms | 42.4 ms | PASS |
| Search (workspace find): full-text results | ≤ 300 ms | 30.9 ms | PASS |
| Undo a block split | ≤ 50 ms | — | NOT SUPPORTED |

**7 of 13 budgets met.**

## Details

- Keystroke p50: 50-block page 9.6 ms; 1,000-block page 10.9 ms (80 keystrokes).
- Cold open of the 1,000-block page: 375 ms.
- Table: first rows 1731 ms; all 10,000 rows drawn after 1761 ms; 46 frames over 25 ms while scrolling.
- Cell edit: shown only after the server answered and the view was redrawn (optimistic: False).
- Ctrl/Cmd+K opens: cyber-palette. Live change samples (ms): 273, 397, 198, 296, 341.
- Undo: block counts before split, after split, after Ctrl/Cmd+Z = [50, 51, 51].

## Raw results

```json
{
 "cell_confirmed": 3061.100000000093,
 "cell_optimistic": false,
 "cell_shown": 3061.100000000093,
 "ctrl_k_opens": "cyber-palette",
 "find_text": 30.899999999906868,
 "find_title": 42.4000000001397,
 "keystroke_big_p50": 10.949999999953434,
 "keystroke_big_p95": 17.609999999857973,
 "keystroke_samples": 80,
 "keystroke_small_p50": 9.550000000046566,
 "keystroke_small_p95": 15.749999999953431,
 "live_change": 296.41080000010334,
 "live_change_samples": [
  273.43007200011016,
  397.27697300008913,
  198.14204399995106,
  296.41080000010334,
  341.18271900001673
 ],
 "open_cold": 155.5999999998603,
 "open_cold_big": 375.0,
 "open_cold_samples": [
  155.5999999998603,
  189.69999999995343,
  145.19999999995343
 ],
 "open_warm": 76.30000000004657,
 "open_warm_samples": [
  73.5999999998603,
  90.89999999990687,
  102.9000000001397,
  69.9000000001397,
  66.60000000009313,
  98.30000000004657,
  76.30000000004657,
  69.80000000004657,
  76.39999999990687,
  60.300000000046566,
  88.69999999995343,
  104.5,
  99.80000000004657,
  26.100000000093132,
  51.9000000001397
 ],
 "palette_title": 32.800000000046566,
 "table_all_rows": 1761.3000000000466,
 "table_first_rows": 1731.0999999998603,
 "table_long_frames": 46,
 "table_rendered_rows": 10000,
 "table_scroll_fps": 25.48088933299847,
 "undo": null,
 "undo_blocks": [
  50,
  51,
  51
 ]
}
```
