#!/usr/bin/env python
"""Create a normal Cyclone Obsidian vault without overwriting existing notes."""

from __future__ import annotations

import argparse
from pathlib import Path

CATEGORIES = (
    "Agents", "Projects", "People", "Research", "Decisions", "Knowledge",
    "Routines", "Sessions", "Tasks", "Skills", "System", "Inbox", "Archive",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path, help="Windows-visible CycloneVault directory")
    args = parser.parse_args()
    root = args.path.resolve()
    root.mkdir(parents=True, exist_ok=True)
    for category in CATEGORIES:
        (root / category).mkdir(exist_ok=True)
    readme = root / "README.md"
    if not readme.exists():
        readme.write_text(
            "# Cyclone Vault\n\n"
            "This is a normal Obsidian vault. Cyclone writes curated durable knowledge here; "
            "it does not automatically copy every chat message into permanent memory.\n",
            encoding="utf-8",
        )
    print(root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
