from __future__ import annotations

import pytest

from app.memory import VaultMemoryService
from app.mobile_events import MobileTaskEvent, MobileTaskEventType
from app.mobile_memory import MobileMemoryFact, MobileMemoryService
from app.mobile_skill_learning import TraceStep, trace_to_skill_candidate


def test_successful_selector_trace_becomes_disabled_skill_candidate() -> None:
    candidate = trace_to_skill_candidate(
        name="Open battery settings",
        trace=[
            TraceStep(
                tool="phone.open_app",
                params={"package": "com.android.settings"},
                ok=True,
                evidence={"afterFingerprint": "settings-home"},
            ),
            TraceStep(
                tool="phone.click",
                params={"selector": {"text": "Battery"}},
                ok=True,
                evidence={"afterFingerprint": "battery-screen"},
            ),
        ],
    )
    document = candidate.as_document()
    assert document["metadata"]["requiresReview"] is True
    assert document["metadata"]["enabled"] is False
    assert document["steps"][1]["params"]["selector"] == {"text": "Battery"}
    assert document["fallback"][-1] == "human_takeover"


def test_coordinate_only_trace_is_not_learned_as_skill() -> None:
    with pytest.raises(ValueError, match="coordinate-only"):
        trace_to_skill_candidate(
            name="Unstable tap",
            trace=[TraceStep(tool="phone.tap", params={"x": 500, "y": 1200}, ok=True)],
        )


def test_mobile_memory_writes_safe_selector_to_existing_vault(tmp_path) -> None:
    vault = VaultMemoryService(tmp_path)
    memory = MobileMemoryService(vault)
    entry = memory.remember(
        MobileMemoryFact(
            kind="selector",
            package="com.android.settings",
            key="battery_menu",
            value={"text": "Battery", "role": "button"},
            confidence=0.95,
        ),
        project_key="cyclone-mobile",
    )
    assert entry.category == "Skills"
    written = (tmp_path / entry.vault_path).read_text(encoding="utf-8")
    assert "com.android.settings" in written
    assert '"text": "Battery"' in written


def test_mobile_memory_rejects_plaintext_credentials(tmp_path) -> None:
    memory = MobileMemoryService(VaultMemoryService(tmp_path))
    with pytest.raises(ValueError, match="must not be stored"):
        memory.remember(
            MobileMemoryFact(
                kind="screen",
                package="com.example",
                key="login_state",
                value={"username": "victor", "password": "do-not-store"},
            )
        )


def test_mobile_task_events_use_required_audit_vocabulary() -> None:
    event = MobileTaskEvent(
        event_type=MobileTaskEventType.AI_RECOVERY_STARTED,
        task_id="task-1",
        device_id="phone-1",
        payload={"reason": "selector failed"},
    ).envelope()
    assert event["type"] == "AI_RECOVERY_STARTED"
    assert event["taskId"] == "task-1"
    assert event["deviceId"] == "phone-1"
