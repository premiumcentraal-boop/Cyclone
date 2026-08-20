#!/usr/bin/env python3
"""Repository-local entry point for the Cyclone development orchestrator."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PACKAGE_ROOT = ROOT / "tools" / "cyclone-agent-coordinator"
sys.path.insert(0, str(PACKAGE_ROOT))

from cyclone_agent_coordinator.cli import main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(main())
