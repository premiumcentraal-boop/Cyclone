#!/usr/bin/env python3
"""Static production guard for screenshot-free semantic Teamwork Sniper execution."""

from __future__ import annotations

import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_APP_ROOT = ROOT / "apps/teamwork-sniper"

FORBIDDEN = {
    "screencap": re.compile(r"\bscreencap\b", re.IGNORECASE),
    "takeScreenshot": re.compile(r"\btakeScreenshot\b", re.IGNORECASE),
    "MediaProjection": re.compile(r"\bMediaProjection\b", re.IGNORECASE),
    "OCR/image analysis": re.compile(
        r"\bOCR\b|TextRecognizer|TextRecognition|textrecognition|ImageAnalysis|image[-_ ]analysis",
        re.IGNORECASE,
    ),
}
HARDCODED_COORDINATES = (
    re.compile(r"\b(?:tap|click)\s*\(\s*\d+(?:\.\d+)?[fF]?\s*,\s*\d+(?:\.\d+)?[fF]?"),
    re.compile(r"\bswipe\s*\(\s*\d+(?:\.\d+)?[fF]?\s*,\s*\d+(?:\.\d+)?[fF]?"),
    re.compile(r"\b(?:moveTo|lineTo)\s*\(\s*\d+(?:\.\d+)?[fF]?\s*,\s*\d+(?:\.\d+)?[fF]?"),
)
GEOMETRY_API = re.compile(r"\bgetBoundsInScreen\b|\bTYPE_ACCESSIBILITY_OVERLAY\b")
OVERLAY_CLAIM_API = re.compile(r"\bdispatchGesture\b|\bACTION_CLICK\b|\bperformAction\s*\(")
OVERLAY_PATH = Path("app/src/main/java/com/cyclone/teamworksniper/ui/overlay")
# This verified 3.5.3.1 dispatcher predates the visual overlay and resolves an exact semantic node
# before using its live bounds. New geometry belongs only in the dedicated presentation package.
FROZEN_SEMANTIC_DISPATCHER = Path(
    "app/src/main/java/com/cyclone/teamworksniper/runtime/TeamworkAccessibilityService.kt"
)
PRODUCTION_SUFFIXES = {".kt", ".java", ".xml", ".kts", ".gradle"}


def production_files(app_root: Path):
    main = app_root / "app/src/main"
    if not main.is_dir():
        return []
    return [path for path in main.rglob("*") if path.is_file() and path.suffix in PRODUCTION_SUFFIXES]


def audit(app_root: Path) -> list[str]:
    errors: list[str] = []
    for path in production_files(app_root):
        text = path.read_text(encoding="utf-8", errors="replace")
        relative = path.relative_to(app_root)
        for label, pattern in FORBIDDEN.items():
            if pattern.search(text):
                errors.append(f"{path}: forbidden {label} dependency/reference")
        for pattern in HARDCODED_COORDINATES:
            if pattern.search(text):
                errors.append(
                    f"{path}: hardcoded coordinate action is forbidden for Teamwork read/claim execution"
                )
                break
        if GEOMETRY_API.search(text) and not (
            relative.is_relative_to(OVERLAY_PATH) or relative == FROZEN_SEMANTIC_DISPATCHER
        ):
            errors.append(
                f"{path}: accessibility geometry is restricted to overlay presentation"
            )
        if relative.is_relative_to(OVERLAY_PATH) and OVERLAY_CLAIM_API.search(text):
            errors.append(
                f"{path}: overlay presentation must never perform Teamwork claim actions"
            )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=DEFAULT_APP_ROOT)
    parser.add_argument("--require-app", action="store_true")
    args = parser.parse_args()
    if not args.root.is_dir():
        if args.require_app:
            parser.error(f"Teamwork Sniper app missing at {args.root}")
        print("Teamwork Sniper source not integrated; screenshot-free audit skipped")
        return 0
    errors = audit(args.root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(
        "Teamwork Sniper production source audit passed: no screenshot/OCR/image-analysis "
        "or hardcoded coordinate execution references found"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
