"""Semantic agent mentions: the backend meaning behind @agent text.

Cyclone treats mentions as structured references, not colored text:

- ``parse_mentions`` extracts every ``@slug`` token in a message, in first
  occurrence order, deduplicated.
- ``parse_handoff`` extracts an explicit delegation in the form
  ``@HANDOFF @slug: summary`` (optionally followed by ``| acceptance criteria``),
  the only pattern that auto-starts work for another agent. Free-form
  mentions are recorded as references but never spawn work on their own,
  which keeps bot-to-bot chatter from causing delegation loops.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

SLUG_PATTERN = r"[a-z0-9][a-z0-9-]{0,62}"

_MENTION_RE = re.compile(rf"(?<![\w@])@({SLUG_PATTERN})\b", re.IGNORECASE)

_HANDOFF_RE = re.compile(
    rf"@HANDOFF\s+@({SLUG_PATTERN})\s*:\s*(?P<summary>.+?)(?:\s*\|\s*(?P<criteria>.+))?$",
    re.IGNORECASE | re.MULTILINE,
)


_RESERVED_SLUGS = frozenset({"handoff"})

_LEAD_MENTION_RE = re.compile(rf"^@({SLUG_PATTERN})\b", re.IGNORECASE)


def resolve_addressed_slug(body: str, member_slugs: set[str]) -> str | None:
    """Return the slug a message is *addressed to*, or None.

    Only a leading ``@slug`` counts as direct addressing (``@research please
    check this``). Inline mentions (``Chief: delegate to @research``) are
    references to teammates, not addressing — the message still goes to the
    agent the composer selected.
    """
    match = _LEAD_MENTION_RE.match(body.lstrip())
    if match is None:
        return None
    slug = match.group(1).lower()
    if slug in _RESERVED_SLUGS or slug not in member_slugs:
        return None
    return slug


def parse_mentions(text: str) -> list[str]:
    """Return ordered, deduplicated mention slugs found in *text*."""
    seen: set[str] = set()
    slugs: list[str] = []
    for match in _MENTION_RE.finditer(text):
        slug = match.group(1).lower()
        if slug in _RESERVED_SLUGS or slug in seen:
            continue
        seen.add(slug)
        slugs.append(slug)
    return slugs


@dataclass(frozen=True)
class HandoffInstruction:
    to_slug: str
    summary: str
    acceptance_criteria: str | None


def parse_handoffs(text: str) -> list[HandoffInstruction]:
    """Return explicit @HANDOFF delegation instructions found in *text*."""
    instructions: list[HandoffInstruction] = []
    for match in _HANDOFF_RE.finditer(text):
        summary = match.group("summary").strip()
        criteria = match.group("criteria")
        if not summary:
            continue
        instructions.append(
            HandoffInstruction(
                to_slug=match.group(1).lower(),
                summary=summary,
                acceptance_criteria=criteria.strip() if criteria else None,
            )
        )
    return instructions


def crew_context_text(members: list[tuple[str, str]]) -> str:
    """Build the teammate context block injected into crew run instructions."""
    lines = [
        "You are working inside a Cyclone crew conversation. Your teammates here:",
    ]
    for slug, role in members:
        role_text = f" — {role}" if role else ""
        lines.append(f"  @{slug}{role_text}")
    lines.append(
        "To delegate part of the work to a teammate, write a line exactly in the "
        'form: @HANDOFF @slug: short summary of what you need | how to verify it. '
        "Only use @HANDOFF when you truly need a teammate; plain @mentions are "
        "references, not requests."
    )
    return "\n".join(lines)
