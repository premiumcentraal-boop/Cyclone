#!/usr/bin/env python3
"""Plan 58 (alpha.123): one-off probe of OpenRouter's Decisions API for JEV and GPT-6 Luna Decisions.

Run it once with your own key:

    OPENROUTER_API_KEY=sk-or-... python scripts/dev/decisions_probe.py

It answers two questions and records real answers for the tests:

1. Did the old request shape (a ``choices`` array, no ``criteria``) ever work? Before alpha.123 every Cyclone decision
   was sent that way. If JEV rejects it, the router's decision box has been answering nothing.
2. What do JEV and Luna really answer, how fast, and does Luna read an image?

Only synthetic requests are sent (no phone data). The key is read from the environment, sent only as the Authorization
header, and never printed or written. Answers are saved, without their ``id``, to
``apps/mobile/app/src/test/resources/decisions/recorded/``. Standard library only.
"""
from __future__ import annotations

import base64
import json
import os
import statistics
import struct
import sys
import time
import urllib.error
import urllib.request
import zlib
from pathlib import Path

ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
JEV = "~typesafe/jev-latest"
LUNA = "openai/gpt-6-luna-decisions"
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "apps/mobile/app/src/test/resources/decisions/recorded"
REPEATS = 5

ROUTE_OPTIONS = ["instant", "flash", "mind", "ignore"]
ROUTE_INSTRUCTIONS = ("instant: one obvious action on this phone. flash: a few routine steps. mind: needs judgement, "
                      "writing or a real answer. ignore: not meant for the assistant.")
STATE_TEXT = ["Route a request to a phone assistant: do it instantly with one tool, plan it quickly (flash), or think (mind).",
              "Request: open the camera\nApps that may be meant: Camera, Photos"]


def legacy_body(model: str) -> dict:
    """The shape Cyclone sent before alpha.123: state as an object, choice questions with a ``choices`` array."""
    return {"model": model, "state": {"task": STATE_TEXT[0], "situation": STATE_TEXT[1]},
            "questions": {"route": {"type": "choice", "instructions": ROUTE_INSTRUCTIONS, "choices": ROUTE_OPTIONS}}}


def documented_body(model: str) -> dict:
    """The documented shape alpha.123 sends (decisions/DecisionsWire.kt)."""
    return {"model": model, "state": STATE_TEXT,
            "questions": {
                "route": {"type": "choice", "instructions": ROUTE_INSTRUCTIONS, "criteria": {o: o for o in ROUTE_OPTIONS}},
                "risky": {"type": "noul", "instructions": "Does it send, pay, delete or touch an account?",
                          "criteria": {"true": "Something risky would happen.", "false": "Nothing risky."}},
                "difficulty": {"type": "score", "instructions": "How much work is this?",
                               "criteria": ["One obvious action.", "A few routine steps.", "Several apps or choosing.", "Writing or judgement."]},
            },
            "provider": {"sort": "latency"}}


def png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """A solid-colour PNG, built by hand so the probe needs nothing installed."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def image_body(model: str) -> dict:
    url = "data:image/png;base64," + base64.b64encode(png(64, 64, (220, 20, 20))).decode()
    return {"model": model, "state": ["What colour fills the picture?", {"type": "image_url", "image_url": {"url": url}}],
            "questions": {"colour": {"type": "choice", "instructions": "Which colour fills the picture?",
                                     "criteria": {"red": "Mostly red.", "green": "Mostly green.", "blue": "Mostly blue."}}}}


def post(key: str, body: dict) -> tuple[int, float, str]:
    request = urllib.request.Request(ENDPOINT, data=json.dumps(body).encode(), method="POST", headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/premiumcentraal-boop/Cyclone", "X-Title": "Cyclone decisions probe"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, (time.monotonic() - started) * 1000, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, (time.monotonic() - started) * 1000, error.read().decode(errors="replace")[:600]
    except (urllib.error.URLError, TimeoutError) as error:
        return 0, (time.monotonic() - started) * 1000, f"{type(error).__name__}"


def save(name: str, text: str) -> None:
    try:
        data = json.loads(text)
    except ValueError:
        return
    if isinstance(data, dict):
        data.pop("id", None)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    key = os.environ.get("OPENROUTER_API_KEY", "").strip()
    if not key:
        print("Set OPENROUTER_API_KEY first (it is never printed or saved).", file=sys.stderr)
        return 2
    cases = [
        ("jev_legacy_choices", legacy_body(JEV)),
        ("jev_documented", documented_body(JEV)),
        ("luna_documented", documented_body(LUNA)),
        ("luna_image", image_body(LUNA)),
    ]
    results = {}
    for name, body in cases:
        runs = [post(key, body) for _ in range(REPEATS if name != "jev_legacy_choices" else 1)]
        status, _, text = runs[-1]
        ok = [ms for s, ms, _ in runs if s == 200]
        results[name] = {"status": status, "ok": len(ok), "of": len(runs),
                         "p50_ms": round(statistics.median(ok)) if ok else None, "max_ms": round(max(ok)) if ok else None,
                         "answer": text[:300] if status != 200 else json.loads(text).get("answers")}
        save(name, text)
    print(json.dumps(results, indent=2))
    legacy = results["jev_legacy_choices"]["status"]
    print()
    if legacy == 200:
        print("The old shape (choices, no criteria) was accepted: the router's box was answering before alpha.123.")
    else:
        print(f"The old shape was refused (HTTP {legacy}): before alpha.123 the router's decision box answered nothing "
              "and every undecided request went to Flash.")
    colour = results["luna_image"]["answer"]
    if isinstance(colour, dict):
        print(f"Luna read the image: {json.dumps(colour.get('colour'))}")
    print(f"Answers saved to {OUT.relative_to(ROOT)} (no ids, no key).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
