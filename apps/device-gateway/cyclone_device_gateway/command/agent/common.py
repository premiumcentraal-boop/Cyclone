"""What every part of the Manager shares: the provider, the limits, the error a turn ends with, and argument checks."""
from __future__ import annotations

import re
import secrets
from typing import Any

from ...desktop_runtime.v5_contract import INLINE_SECRET
from ..center import CommandError

PROVIDER = {"name": "OpenRouter", "site": "https://openrouter.ai", "keysUrl": "https://openrouter.ai/settings/keys",
            "privacyUrl": "https://openrouter.ai/settings/privacy"}
BASE = "https://openrouter.ai/api/v1"
GRANT = "ai:openrouter"
KEY = re.compile(r"^[A-Za-z0-9._-]{20,300}$")
MODEL_ID = re.compile(r"^[A-Za-z0-9._:/-]{1,120}$")
CONVERSATION_ID = re.compile(r"^ai_[A-Za-z0-9_-]{6,40}$")
PROPOSAL_ID = re.compile(r"^prp_[A-Za-z0-9_-]{6,40}$")
AUTONOMY = ("propose", "workspace")
DEFAULTS: dict[str, Any] = {"model": None, "dailyCapUsd": 2.0, "monthlyCapUsd": 30.0, "privateOnly": True,
                            "autonomy": "propose", "instructions": ""}
MAX_STEPS = 10
MAX_CALLS_PER_STEP = 8
MAX_OWNER_TEXT = 4_000
MAX_INSTRUCTIONS = 4_000
MAX_TOOL_RESULT = 12_000
MAX_CONTEXT_CHARS = 120_000
MAX_ANSWER_TOKENS = 4_000
MAX_CONVERSATIONS = 500
CATALOGUE_TTL_MS = 60 * 60_000


class AiError(RuntimeError):
    """A turn that could not go on (no key, a budget reached, the provider refused)."""


def _new(prefix: str) -> str:
    return f"{prefix}_{secrets.token_urlsafe(12)}"


def _only(value: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = set(value) - allowed
    if extra:
        raise CommandError(f"{label}: unknown field {sorted(extra)[0]}.")


def _price(value: Any) -> float | None:
    try:
        price = float(value)
    except (TypeError, ValueError):
        return None
    return None if price < 0 else round(price * 1_000_000, 6)


def _money(value: Any, label: str, low: float, high: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not low <= float(value) <= high:
        raise CommandError(f"{label} is between {low:g} and {high:g} US dollars.")
    return round(float(value), 2)


def _text_arg(args: dict[str, Any], name: str, limit: int, *, required: bool = True) -> str:
    value = args.get(name)
    if value is None and not required:
        return ""
    if not isinstance(value, str) or (required and not value.strip()):
        raise CommandError(f"{name} is required text.")
    value = value.strip()
    if len(value) > limit:
        raise CommandError(f"{name} is at most {limit} characters.")
    if INLINE_SECRET.search(value):
        raise CommandError("That looks like a password, code or key. Never write secrets; the vault keeps them.")
    return value


def _schema(properties: dict[str, Any], required: list[str]) -> dict[str, Any]:
    return {"type": "object", "properties": properties, "required": required, "additionalProperties": False}
