from uuid import uuid4

import pytest

from app.recovery import (
    HermesRunObservation,
    ObservableRunState,
    RecoveryAction,
    RecoveryTask,
    RecoveryValidationError,
    ReviewerAcceptanceRecord,
    ReviewerDecision,
    TaskLifecycle,
    plan_orphaned_run_recovery,
    resolve_reviewer_acceptance,
)


def make_task(*, status: TaskLifecycle = TaskLifecycle.RUNNING, run_id: str | None = "run_123") -> RecoveryTask:
    return RecoveryTask(id=uuid4(), status=status, hermes_run_id=run_id)


@pytest.mark.parametrize(
    ("found", "status", "expected"),
    [
        (True, "completed", ObservableRunState.COMPLETED),
        (True, "succeeded", ObservableRunState.COMPLETED),
        (True, "failed", ObservableRunState.FAILED),
        (True, "cancelled", ObservableRunState.CANCELLED),
        (True, "running", ObservableRunState.RUNNING),
        # Hermes returns HTTP 404 for a run it no longer retains.  That is a
        # missing record, not evidence of a completed run.
        (False, "HTTP 404 run not found", ObservableRunState.MISSING),
        (None, None, ObservableRunState.UNKNOWN),
        (True, "new-unrecognised-state", ObservableRunState.UNKNOWN),
    ],
)
def test_observation_classifies_only_observable_hermes_states(
    found: bool | None, status: str | None, expected: ObservableRunState
) -> None:
    assert HermesRunObservation(found=found, status=status).state is expected


@pytest.mark.parametrize(
    ("observation", "action", "target", "requires_reviewer"),
    [
        (HermesRunObservation(found=True, status="running"), RecoveryAction.RESUME_OBSERVATION, TaskLifecycle.RUNNING, False),
        (HermesRunObservation(found=True, status="completed"), RecoveryAction.REQUEST_REVIEW, TaskLifecycle.AWAITING_REVIEW, True),
        (HermesRunObservation(found=True, status="failed"), RecoveryAction.MARK_FAILED, TaskLifecycle.FAILED, False),
        (HermesRunObservation(found=True, status="cancelled"), RecoveryAction.MARK_CANCELLED, TaskLifecycle.CANCELLED, False),
        (HermesRunObservation(found=False, detail="HTTP 404 run not found"), RecoveryAction.BLOCK_FOR_OPERATOR, TaskLifecycle.BLOCKED, False),
        (HermesRunObservation(found=None, detail="network unavailable"), RecoveryAction.BLOCK_FOR_OPERATOR, TaskLifecycle.BLOCKED, False),
    ],
)
def test_recovery_plans_are_safe_and_never_complete_directly(
    observation: HermesRunObservation,
    action: RecoveryAction,
    target: TaskLifecycle,
    requires_reviewer: bool,
) -> None:
    plan = plan_orphaned_run_recovery(make_task(), observation)

    assert plan.action is action
    assert plan.target_task_status is target
    assert plan.target_task_status is not TaskLifecycle.COMPLETED
    assert plan.requires_reviewer is requires_reviewer


def test_recovery_plan_is_idempotent_for_same_task_and_observation() -> None:
    task = make_task()
    observation = HermesRunObservation(found=True, status="completed")

    first = plan_orphaned_run_recovery(task, observation)
    second = plan_orphaned_run_recovery(task, observation)

    assert first == second
    assert first.idempotency_key == (
        f"run-recovery:v1:{task.id}:run_123:running:completed:request_review"
    )


def test_terminal_task_is_never_overwritten_by_late_hermes_observation() -> None:
    task = make_task(status=TaskLifecycle.COMPLETED)

    plan = plan_orphaned_run_recovery(task, HermesRunObservation(found=True, status="failed"))

    assert plan.action is RecoveryAction.NO_ACTION
    assert plan.target_task_status is TaskLifecycle.COMPLETED


def test_missing_run_id_blocks_instead_of_inventing_a_completion() -> None:
    plan = plan_orphaned_run_recovery(
        make_task(run_id=None), HermesRunObservation(found=True, status="completed")
    )

    assert plan.observed_state is ObservableRunState.MISSING
    assert plan.action is RecoveryAction.BLOCK_FOR_OPERATOR
    assert plan.target_task_status is TaskLifecycle.BLOCKED


def test_only_matching_explicit_reviewer_acceptance_completes_a_task() -> None:
    task = make_task(status=TaskLifecycle.AWAITING_REVIEW, run_id="run_verified")
    acceptance = ReviewerAcceptanceRecord.create(
        task_id=task.id,
        reviewer_agent_id=uuid4(),
        reviewed_run_id="run_verified",
        decision=ReviewerDecision.ACCEPTED,
        evidence_summary="Reviewer ran the requested tests and inspected the output.",
        evidence={"tests": "passed", "artifact_sha256": "a" * 64},
    )

    resolution = resolve_reviewer_acceptance(
        task=task, completed_run_id="run_verified", acceptance=acceptance
    )

    assert resolution.target_task_status is TaskLifecycle.COMPLETED
    assert acceptance.repository_values()["decision"] == "accepted"
    assert acceptance.idempotency_key.startswith("reviewer-acceptance:v1:")


def test_reviewer_cannot_complete_a_stale_or_unreviewed_run() -> None:
    task = make_task(status=TaskLifecycle.RUNNING, run_id="run_123")
    acceptance = ReviewerAcceptanceRecord.create(
        task_id=task.id,
        reviewer_agent_id=uuid4(),
        reviewed_run_id="run_123",
        decision=ReviewerDecision.ACCEPTED,
        evidence_summary="Looks good.",
    )

    with pytest.raises(RecoveryValidationError, match="awaiting review"):
        resolve_reviewer_acceptance(task=task, completed_run_id="run_123", acceptance=acceptance)

    awaiting_review = RecoveryTask(id=task.id, status=TaskLifecycle.AWAITING_REVIEW, hermes_run_id="run_456")
    with pytest.raises(RecoveryValidationError, match="does not match"):
        resolve_reviewer_acceptance(task=awaiting_review, completed_run_id="run_456", acceptance=acceptance)


def test_reviewer_change_request_never_completes_a_task() -> None:
    task = make_task(status=TaskLifecycle.AWAITING_REVIEW)
    acceptance = ReviewerAcceptanceRecord.create(
        task_id=task.id,
        reviewer_agent_id=uuid4(),
        reviewed_run_id="run_123",
        decision=ReviewerDecision.CHANGES_REQUESTED,
        evidence_summary="The implementation misses the required restart assertion.",
    )

    resolution = resolve_reviewer_acceptance(task=task, completed_run_id="run_123", acceptance=acceptance)

    assert resolution.target_task_status is TaskLifecycle.CHANGES_REQUESTED
