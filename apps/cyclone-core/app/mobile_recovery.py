"""Deterministic escalation ladder for mobile workflow failures."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class RecoveryStage(str, Enum):
    DETERMINISTIC_RETRY = "deterministic_retry"
    FRESH_OBSERVE = "fresh_observe"
    KNOWN_RECOVERY = "known_recovery"
    AI_RECOVERY = "ai_recovery"
    HUMAN_TAKEOVER = "human_takeover"


@dataclass(frozen=True)
class RecoveryDecision:
    stage: RecoveryStage
    reason: str
    consumes_ai_tokens: bool


def select_recovery_stage(
    *,
    deterministic_attempts: int,
    fresh_observe_attempted: bool,
    known_recovery_available: bool,
    ai_recovery_attempted: bool,
) -> RecoveryDecision:
    if deterministic_attempts < 3:
        return RecoveryDecision(
            RecoveryStage.DETERMINISTIC_RETRY,
            "Retry the same typed action before spending model tokens.",
            False,
        )
    if not fresh_observe_attempted:
        return RecoveryDecision(
            RecoveryStage.FRESH_OBSERVE,
            "Refresh structured phone state before escalating.",
            False,
        )
    if known_recovery_available:
        return RecoveryDecision(
            RecoveryStage.KNOWN_RECOVERY,
            "Apply the persisted recovery strategy.",
            False,
        )
    if not ai_recovery_attempted:
        return RecoveryDecision(
            RecoveryStage.AI_RECOVERY,
            "Deterministic recovery is exhausted; wake Hermes for bounded recovery.",
            True,
        )
    return RecoveryDecision(
        RecoveryStage.HUMAN_TAKEOVER,
        "AI recovery did not establish a safe deterministic continuation.",
        False,
    )
