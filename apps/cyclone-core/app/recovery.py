"""Deterministic recovery and reviewer-verification decisions for Hermes runs.

This module intentionally has no database or HTTP dependency.  Startup recovery
can therefore make the same decision after a Core restart, a worker retry, or a
manual operator inspection.  The caller is responsible for persisting the plan
with ``RecoveryPlan.idempotency_key`` before carrying it out.

The safety invariant is deliberately simple: a Hermes terminal-success signal
means *ready for review*, never ``completed``.  Only an explicit accepted review
can produce a completed task recommendation.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from hashlib import sha256
from typing import Final
from uuid import UUID


class RecoveryValidationError(ValueError):
    """A recovery/review record is internally inconsistent."""


class TaskLifecycle(str, Enum):
    """The task states relevant to recovery, mirroring the Core contract."""

    QUEUED = "queued"
    RUNNING = "running"
    AWAITING_APPROVAL = "awaiting_approval"
    AWAITING_REVIEW = "awaiting_review"
    CHANGES_REQUESTED = "changes_requested"
    COMPLETED = "completed"
    BLOCKED = "blocked"
    FAILED = "failed"
    CANCELLED = "cancelled"


class ObservableRunState(str, Enum):
    """What Core can establish about a previously started Hermes run."""

    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    RUNNING = "running"
    MISSING = "missing"
    UNKNOWN = "unknown"


class RecoveryAction(str, Enum):
    """Idempotent actions an integration layer may carry out exactly once."""

    NO_ACTION = "no_action"
    RESUME_OBSERVATION = "resume_observation"
    REQUEST_REVIEW = "request_review"
    MARK_FAILED = "mark_failed"
    MARK_CANCELLED = "mark_cancelled"
    BLOCK_FOR_OPERATOR = "block_for_operator"


class ReviewerDecision(str, Enum):
    """A reviewer may accept work or return it for changes; they cannot skip review."""

    ACCEPTED = "accepted"
    CHANGES_REQUESTED = "changes_requested"


_TERMINAL_TASK_STATES: Final[frozenset[TaskLifecycle]] = frozenset(
    {TaskLifecycle.COMPLETED, TaskLifecycle.FAILED, TaskLifecycle.CANCELLED}
)

_SUCCESS_STATUSES: Final[frozenset[str]] = frozenset(
    {"completed", "complete", "succeeded", "success", "done", "finished"}
)
_FAILURE_STATUSES: Final[frozenset[str]] = frozenset(
    {"failed", "error", "errored", "timed_out", "timeout"}
)
_CANCELLED_STATUSES: Final[frozenset[str]] = frozenset(
    {"cancelled", "canceled", "aborted"}
)
_RUNNING_STATUSES: Final[frozenset[str]] = frozenset(
    {"queued", "pending", "running", "started", "in_progress", "waiting", "awaiting_approval"}
)


@dataclass(frozen=True)
class RecoveryTask:
    """Minimal durable task data needed to recover an orphaned observer."""

    id: UUID
    status: TaskLifecycle
    hermes_run_id: str | None

    def __post_init__(self) -> None:
        if self.hermes_run_id is not None and not self.hermes_run_id.strip():
            raise RecoveryValidationError("A Hermes run ID must not be blank")


@dataclass(frozen=True)
class HermesRunObservation:
    """A bounded, observable Hermes lookup result.

    ``found=False`` is distinct from an unavailable/ambiguous lookup.  The
    latter uses ``found=None`` and becomes ``UNKNOWN``, which is intentionally
    blocked rather than retried or completed automatically.
    """

    found: bool | None
    status: str | None = None
    detail: str | None = None

    @property
    def state(self) -> ObservableRunState:
        if self.found is False:
            return ObservableRunState.MISSING
        if self.found is not True:
            return ObservableRunState.UNKNOWN
        if not self.status:
            return ObservableRunState.UNKNOWN
        normalized = self.status.strip().lower().replace("-", "_").replace(" ", "_")
        if normalized in _SUCCESS_STATUSES:
            return ObservableRunState.COMPLETED
        if normalized in _FAILURE_STATUSES:
            return ObservableRunState.FAILED
        if normalized in _CANCELLED_STATUSES:
            return ObservableRunState.CANCELLED
        if normalized in _RUNNING_STATUSES:
            return ObservableRunState.RUNNING
        return ObservableRunState.UNKNOWN


@dataclass(frozen=True)
class RecoveryPlan:
    """A stable recommendation; the repository must persist its key before acting."""

    task_id: UUID
    hermes_run_id: str | None
    observed_state: ObservableRunState
    action: RecoveryAction
    target_task_status: TaskLifecycle
    reason: str
    idempotency_key: str
    requires_reviewer: bool = False

    def __post_init__(self) -> None:
        if self.target_task_status is TaskLifecycle.COMPLETED and self.action is not RecoveryAction.NO_ACTION:
            raise RecoveryValidationError(
                "Run recovery can never complete a task; reviewer acceptance is required."
            )
        if self.action is RecoveryAction.REQUEST_REVIEW and not self.requires_reviewer:
            raise RecoveryValidationError("A review request must require a reviewer")


@dataclass(frozen=True)
class ReviewerAcceptanceRecord:
    """Append-only reviewer decision persisted by ``reviewer_acceptances``.

    ``evidence_summary`` is deliberately human-readable while ``evidence`` is
    a JSON-compatible mapping (checks, links, hashes, or test results).  The
    idempotency key stops duplicate writes when a reviewer client retries.
    """

    task_id: UUID
    reviewer_agent_id: UUID
    reviewed_run_id: str
    decision: ReviewerDecision
    evidence_summary: str
    evidence: dict[str, object]
    idempotency_key: str

    def __post_init__(self) -> None:
        if not self.reviewed_run_id.strip():
            raise RecoveryValidationError("A reviewer acceptance must name the reviewed Hermes run")
        if not self.evidence_summary.strip():
            raise RecoveryValidationError("A reviewer decision requires an evidence summary")
        if not self.idempotency_key.strip():
            raise RecoveryValidationError("A reviewer decision requires an idempotency key")

    @classmethod
    def create(
        cls,
        *,
        task_id: UUID,
        reviewer_agent_id: UUID,
        reviewed_run_id: str,
        decision: ReviewerDecision,
        evidence_summary: str,
        evidence: dict[str, object] | None = None,
    ) -> "ReviewerAcceptanceRecord":
        """Create a stable retry key from the immutable review decision."""

        digest_input = "|".join(
            (str(task_id), str(reviewer_agent_id), reviewed_run_id, decision.value, evidence_summary)
        )
        key = f"reviewer-acceptance:v1:{sha256(digest_input.encode()).hexdigest()}"
        return cls(
            task_id=task_id,
            reviewer_agent_id=reviewer_agent_id,
            reviewed_run_id=reviewed_run_id,
            decision=decision,
            evidence_summary=evidence_summary,
            evidence=dict(evidence or {}),
            idempotency_key=key,
        )

    def repository_values(self) -> dict[str, object]:
        """Values for a future repository insert without exposing database concerns."""

        return {
            "task_id": self.task_id,
            "reviewer_agent_id": self.reviewer_agent_id,
            "reviewed_run_id": self.reviewed_run_id,
            "decision": self.decision.value,
            "evidence_summary": self.evidence_summary,
            "evidence": dict(self.evidence),
            "idempotency_key": self.idempotency_key,
        }


@dataclass(frozen=True)
class ReviewResolution:
    """The only safe task status transition driven by a reviewer record."""

    task_id: UUID
    target_task_status: TaskLifecycle
    reason: str


def _idempotency_key(
    *, task: RecoveryTask, observed_state: ObservableRunState, action: RecoveryAction
) -> str:
    run_id = task.hermes_run_id or "no-run-id"
    return f"run-recovery:v1:{task.id}:{run_id}:{task.status.value}:{observed_state.value}:{action.value}"


def _plan(
    task: RecoveryTask,
    observed_state: ObservableRunState,
    action: RecoveryAction,
    target_task_status: TaskLifecycle,
    reason: str,
    *,
    requires_reviewer: bool = False,
) -> RecoveryPlan:
    return RecoveryPlan(
        task_id=task.id,
        hermes_run_id=task.hermes_run_id,
        observed_state=observed_state,
        action=action,
        target_task_status=target_task_status,
        reason=reason,
        idempotency_key=_idempotency_key(task=task, observed_state=observed_state, action=action),
        requires_reviewer=requires_reviewer,
    )


def plan_orphaned_run_recovery(task: RecoveryTask, observation: HermesRunObservation) -> RecoveryPlan:
    """Classify one orphaned task/run pair and return its safe recovery plan.

    Calling this repeatedly with the same durable task and observed Hermes state
    yields the same action key.  The caller should record that key atomically;
    a retry then sees the completed action and does not duplicate messages or
    state transitions.
    """

    if task.status in _TERMINAL_TASK_STATES:
        return _plan(
            task,
            observation.state,
            RecoveryAction.NO_ACTION,
            task.status,
            "The task is already terminal; recovery must not reopen or overwrite it.",
        )

    if task.hermes_run_id is None:
        return _plan(
            task,
            ObservableRunState.MISSING,
            RecoveryAction.BLOCK_FOR_OPERATOR,
            TaskLifecycle.BLOCKED,
            "The task was running without a durable Hermes run ID; no completion can be inferred.",
        )

    state = observation.state
    if state is ObservableRunState.RUNNING:
        return _plan(
            task,
            state,
            RecoveryAction.RESUME_OBSERVATION,
            TaskLifecycle.RUNNING,
            "Hermes still reports this run as active; resume bounded observation.",
        )
    if state is ObservableRunState.COMPLETED:
        return _plan(
            task,
            state,
            RecoveryAction.REQUEST_REVIEW,
            TaskLifecycle.AWAITING_REVIEW,
            "Hermes reported a terminal result; it requires explicit reviewer acceptance before completion.",
            requires_reviewer=True,
        )
    if state is ObservableRunState.FAILED:
        return _plan(
            task,
            state,
            RecoveryAction.MARK_FAILED,
            TaskLifecycle.FAILED,
            "Hermes reported a terminal failure.",
        )
    if state is ObservableRunState.CANCELLED:
        return _plan(
            task,
            state,
            RecoveryAction.MARK_CANCELLED,
            TaskLifecycle.CANCELLED,
            "Hermes reported that the run was cancelled.",
        )
    if state is ObservableRunState.MISSING:
        return _plan(
            task,
            state,
            RecoveryAction.BLOCK_FOR_OPERATOR,
            TaskLifecycle.BLOCKED,
            "Hermes has no record of the run; no completion or retry is assumed automatically.",
        )
    return _plan(
        task,
        state,
        RecoveryAction.BLOCK_FOR_OPERATOR,
        TaskLifecycle.BLOCKED,
        "Hermes state is unavailable or unrecognised; operator inspection is required.",
    )


def resolve_reviewer_acceptance(
    *,
    task: RecoveryTask,
    completed_run_id: str,
    acceptance: ReviewerAcceptanceRecord,
) -> ReviewResolution:
    """Resolve review without allowing an unverified or stale run to complete.

    A successful reviewer decision is allowed only for the exact Hermes run
    which was classified completed and moved into ``awaiting_review``.  A
    change request is safe to record from that state as well.
    """

    if task.status is not TaskLifecycle.AWAITING_REVIEW:
        raise RecoveryValidationError("Only a task awaiting review can be resolved by a reviewer")
    if not task.hermes_run_id or task.hermes_run_id != completed_run_id:
        raise RecoveryValidationError("Reviewer acceptance must match the task's durable Hermes run ID")
    if acceptance.task_id != task.id or acceptance.reviewed_run_id != completed_run_id:
        raise RecoveryValidationError("Reviewer acceptance does not match the task/run under review")
    if acceptance.decision is ReviewerDecision.ACCEPTED:
        return ReviewResolution(
            task_id=task.id,
            target_task_status=TaskLifecycle.COMPLETED,
            reason="A reviewer explicitly accepted the completed Hermes run.",
        )
    return ReviewResolution(
        task_id=task.id,
        target_task_status=TaskLifecycle.CHANGES_REQUESTED,
        reason="The reviewer requested changes to the completed Hermes run.",
    )
