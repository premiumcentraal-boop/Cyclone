# Copyright 2026 Premium Centraal / Cyclone PC Glass
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

"""Map a natural-language goal to Artemis run knobs using Cyclone guidelines.

v1 is start-of-run only (no mid-run Flash→Pro escalation). Keeps FlashRunner /
Pro graph engines; Cyclone owns *which* profile and Pro tuning to send.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from artemis.utils.file import load_jsonc
from artemis.utils.logger import get_logger

logger = get_logger(__name__)

_DEFAULT_GUIDELINES = Path(__file__).resolve().parents[2] / "config" / "cyclone_auto_guidelines.jsonc"


@dataclass(frozen=True)
class AutoResolution:
    """Resolved Artemis /api/run fields for a Cyclone Auto launch."""

    tier: str  # easy | medium | hard
    profile: str  # flash | pro
    verification_level: str | None
    explorer_mode: str | None
    reason: str

    def as_run_overrides(self) -> dict[str, Any]:
        out: dict[str, Any] = {"profile": self.profile}
        if self.verification_level:
            out["verification_level"] = self.verification_level
        if self.explorer_mode:
            out["explorer_mode"] = self.explorer_mode
        return out


def _load_guidelines(path: Path | None = None) -> dict[str, Any]:
    cfg_path = path or _DEFAULT_GUIDELINES
    if not cfg_path.exists():
        logger.warning(f"cyclone_auto_guidelines missing at {cfg_path}; using built-in defaults")
        return {
            "tiers": {
                "easy": {"profile": "flash"},
                "medium": {"profile": "flash"},
                "hard": {
                    "profile": "pro",
                    "verification_level": "checkpoints",
                    "explorer_mode": "pro",
                },
            },
            "hard_keywords": [
                "monitor",
                "every",
                "poll",
                "diagnose",
                "extract",
                "report",
                "then",
                "after that",
                "multi",
                "several",
                "compare",
                "book",
                "checkout",
                "pay",
                "form",
            ],
            "easy_patterns": [
                r"^\s*open\s+[\w .'-]+\s*$",
                r"^\s*launch\s+[\w .'-]+\s*$",
                r"^\s*go to\s+[\w .'-]+\s*$",
            ],
        }
    with open(cfg_path, encoding="utf-8") as f:
        return load_jsonc(f)


def assess_tier(goal: str, guidelines: dict[str, Any] | None = None) -> tuple[str, str]:
    """Return (tier, reason) for a goal string."""
    g = (guidelines or _load_guidelines())
    text = (goal or "").strip()
    lower = text.lower()

    for pat in g.get("easy_patterns") or []:
        try:
            if re.search(pat, text, flags=re.IGNORECASE):
                return "easy", f"matched easy_pattern {pat!r}"
        except re.error:
            continue

    hard_hits = [k for k in (g.get("hard_keywords") or []) if k.lower() in lower]
    # Multi-step cues
    if " and then " in lower or lower.count(" then ") >= 1:
        hard_hits.append("then-chain")
    if hard_hits:
        return "hard", "hard cues: " + ", ".join(hard_hits[:6])

    # Medium: open X and do something light, or anything not easy/hard
    if re.search(r"\b(open|launch|go to)\b", lower) and len(text.split()) > 4:
        return "medium", "open/launch with extra instructions"
    return "medium", "default medium"


def resolve_auto(
    goal: str,
    *,
    guidelines_path: Path | None = None,
    user_verification: str | None = None,
    user_explorer: str | None = None,
) -> AutoResolution:
    """Classify goal and map to Artemis profile + optional Pro tuning.

    Explicit user verification/explorer (when provided) win over guidelines for
    that field — used when a preset pins tuning under Auto.
    """
    guidelines = _load_guidelines(guidelines_path)
    tier, reason = assess_tier(goal, guidelines)
    tier_cfg = (guidelines.get("tiers") or {}).get(tier) or {"profile": "flash"}
    profile = str(tier_cfg.get("profile") or "flash").lower()
    if profile not in {"flash", "pro"}:
        profile = "flash"

    verification = user_verification or tier_cfg.get("verification_level")
    explorer = user_explorer or tier_cfg.get("explorer_mode")
    if profile == "flash":
        # Flash ignores Pro tuning; keep None so enqueue stays clean.
        verification = None
        explorer = None

    return AutoResolution(
        tier=tier,
        profile=profile,
        verification_level=str(verification) if verification else None,
        explorer_mode=str(explorer) if explorer else None,
        reason=reason,
    )
