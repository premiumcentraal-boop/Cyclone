# Glass benchmark and acceptance script

Plan 76, stage 0 (run 0b). Measures today's Glass against the plan's speed budgets (§5.2), audits its look against the
design system (§5.3), and runs the experience acceptance script (§5.7), all on a real gateway in a real browser.

Nothing here touches the owner's Cyclone. Each run starts a gateway on a free loopback port, in a new temporary runtime
folder, with synthetic data (plan 76 test-data policy), and deletes it afterwards.

## Set up once

```
python -m pip install -e 'apps/device-gateway[test]' -r tools/glass-bench/requirements.txt
cd apps/glass && npm ci && npm run build && cd ../..
```

The benchmark uses the Chromium at `/opt/pw-browsers/chromium-1243/chrome-linux64/chrome` when it exists, otherwise
Playwright's own (`python -m playwright install chromium`). Set `GLASS_BENCH_CHROMIUM` to use another.

## Run

| Command | What it does | Time |
|---|---|---|
| `python tools/glass-bench/run_bench.py --out report.md` | Every §5.2 budget, graded PASS, FAIL or NOT SUPPORTED | about 3 minutes |
| `python tools/glass-bench/run_acceptance.py --out report.md` | The acceptance script's tasks, each PASS, MISSING or FAIL | about 2 minutes |
| `python -m glass_bench.design_audit report.md` (from `tools/glass-bench`) | Colours, sizes, spacing, radii and glyphs outside the design system | seconds |

`--rows N` sets the size of the big table (10,000 by default, today's cap).

## How to read it

- Times are taken in the page with `performance.now()`. Waiting for the screen polls once per frame, so a time can be up
  to about 16 ms late, never early.
- A keystroke is timed until the next frame starts, so one handled within its frame reads 0 to 16.7 ms.
- Compare runs on the same machine only. Each report names its machine.
- The acceptance script is run by an automated stand-in. Its `MISSING` rows name the plan 76 stage that adds the
  feature. A run by the owner is still owed (plan 76 §5.7).

CI checks the grading, statistics, audit and report logic (`scripts/ci/tests/test_glass_bench_tools.py`). The browser
runs happen on demand and at the end of each stage, because shared CI machines are too noisy for millisecond budgets.
