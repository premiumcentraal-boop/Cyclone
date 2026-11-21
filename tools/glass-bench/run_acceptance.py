"""Run the experience acceptance script on today's Glass and write the report."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from glass_bench.acceptance import report, run  # noqa: E402
from glass_bench.harness import browser, gateway  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=10_000)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    with gateway(rows=args.rows) as (gw, fx), browser() as context:
        text = report(run(context, gw, fx))
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
