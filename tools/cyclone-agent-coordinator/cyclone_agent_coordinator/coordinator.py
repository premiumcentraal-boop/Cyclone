"""Cyclone-native durable development task coordinator."""

from __future__ import annotations

import time
import uuid
from collections.abc import Callable, Iterable
from typing import Any

from .errors import (
    AuthorizationError,
    ConflictError,
    DependencyBlockedError,
    NotFoundError,
    StaleAttemptError,
    TransitionError,
    ValidationError,
)
from .models import (
    CompletionBundle,
    MailboxMessage,
    TaskRecord,
    TaskStatus,
    TeamEvent,
    TeamRecord,
)
from .store import FileTeamStore
from .validation import (
    ownership_violations,
    require_identifier,
    require_sha,
    validate_completion_bundle,
    validate_path_rules,
    validate_task_graph,
)


ALLOWED_TRANSITIONS: dict[TaskStatus, set[TaskStatus]] = {
    TaskStatus.BLOCKED: {TaskStatus.READY, TaskStatus.CANCELLED},
    TaskStatus.READY: {TaskStatus.BLOCKED, TaskStatus.CLAIMED, TaskStatus.CANCELLED},
    TaskStatus.CLAIMED: {
        TaskStatus.RUNNING,
        TaskStatus.READY,
        TaskStatus.BLOCKED,
        TaskStatus.FAILED,
        TaskStatus.CANCELLED,
    },
    TaskStatus.RUNNING: {
        TaskStatus.REVIEW,
        TaskStatus.READY,
        TaskStatus.BLOCKED,
        TaskStatus.FAILED,
        TaskStatus.CANCELLED,
    },
    TaskStatus.REVIEW: {TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED},
    TaskStatus.DONE: set(),
    TaskStatus.FAILED: {TaskStatus.READY, TaskStatus.BLOCKED, TaskStatus.CANCELLED},
    TaskStatus.CANCELLED: set(),
}


class CycloneAgentCoordinator:
    """Captain-controlled task DAG with durable attempts, evidence, and journals."""

    def __init__(
        self,
        store: FileTeamStore,
        *,
        clock: Callable[[], int] | None = None,
        id_factory: Callable[[], str] | None = None,
    ) -> None:
        self.store = store
        self.clock = clock or (lambda: int(time.time()))
        self.id_factory = id_factory or (lambda: uuid.uuid4().hex)

    def create_team(
        self,
        team_id: str,
        name: str,
        captain_id: str,
        base_sha: str,
    ) -> TeamRecord:
        require_identifier(team_id, "team_id")
        require_identifier(captain_id, "captain_id")
        require_sha(base_sha, "team base_sha")
        if not name.strip():
            raise ValidationError("Team name must not be blank")
        now = self.clock()
        team = TeamRecord(
            team_id=team_id,
            name=name.strip(),
            captain_id=captain_id,
            base_sha=base_sha,
            members=[captain_id],
            created_at=now,
            updated_at=now,
        )
        self.store.create(team)
        self._append_events(team_id, [("team.created", None, captain_id, {"base_sha": base_sha})])
        return team

    def list_teams(self) -> list[TeamRecord]:
        return [self.get_team(team_id) for team_id in self.store.list_team_ids()]

    def get_team(self, team_id: str) -> TeamRecord:
        return self._load_recovered(team_id)

    def add_member(
        self,
        team_id: str,
        *,
        actor_id: str,
        member_id: str,
        base_sha: str,
    ) -> TeamRecord:
        team = self._load_recovered(team_id)
        self._require_captain(team, actor_id)
        self._require_base(team, base_sha)
        require_identifier(member_id, "member_id")
        if member_id in team.members:
            raise ConflictError(f"Team member already exists: {member_id}")
        team.members = sorted(team.members + [member_id])
        self._save(team, [("member.added", None, actor_id, {"member_id": member_id})])
        return team

    def remove_member(
        self,
        team_id: str,
        *,
        actor_id: str,
        member_id: str,
        base_sha: str,
    ) -> TeamRecord:
        team = self._load_recovered(team_id)
        self._require_captain(team, actor_id)
        self._require_base(team, base_sha)
        if member_id == team.captain_id:
            raise ValidationError("The team captain cannot be removed")
        if member_id not in team.members:
            raise NotFoundError(f"Team member not found: {member_id}")
        active = sorted(
            task.task_id
            for task in team.tasks.values()
            if task.lease_owner == member_id
            and task.status in {TaskStatus.CLAIMED, TaskStatus.RUNNING, TaskStatus.REVIEW}
        )
        if active:
            raise ConflictError(f"Member {member_id} owns active tasks: {', '.join(active)}")
        team.members.remove(member_id)
        self._save(team, [("member.removed", None, actor_id, {"member_id": member_id})])
        return team

    def add_task(
        self,
        team_id: str,
        *,
        actor_id: str,
        task_id: str,
        owner_lane: str,
        owned_paths: Iterable[str],
        forbidden_paths: Iterable[str],
        dependencies: Iterable[str] = (),
        parent_task: str | None = None,
        base_sha: str,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_captain(team, actor_id)
        self._require_base(team, base_sha)
        require_identifier(task_id, "task_id")
        if task_id in team.tasks:
            raise ConflictError(f"Task already exists: {task_id}")
        if not owner_lane.strip():
            raise ValidationError("owner_lane must not be blank")

        dependency_list = list(dependencies)
        if len(set(dependency_list)) != len(dependency_list):
            raise ValidationError("Task dependencies must be unique")
        dependency_list = sorted(dependency_list)
        for dependency in dependency_list:
            require_identifier(dependency, "dependency task_id")
            if dependency not in team.tasks:
                raise ValidationError(f"Missing dependency: {dependency}")
        if parent_task is not None:
            require_identifier(parent_task, "parent_task")
            if parent_task not in team.tasks:
                raise ValidationError(f"Missing parent task: {parent_task}")
        owned, forbidden = validate_path_rules(owned_paths, forbidden_paths)
        now = self.clock()
        task = TaskRecord(
            team_id=team.team_id,
            task_id=task_id,
            parent_task=parent_task,
            owner_lane=owner_lane.strip(),
            owned_paths=owned,
            forbidden_paths=forbidden,
            dependencies=dependency_list,
            base_sha=team.base_sha,
            status=(
                TaskStatus.READY
                if self._dependencies_done(team, dependency_list)
                else TaskStatus.BLOCKED
            ),
            created_at=now,
            updated_at=now,
        )
        team.tasks[task_id] = task
        validate_task_graph(team)
        self._save(
            team,
            [
                (
                    "task.created",
                    task_id,
                    actor_id,
                    {"status": task.status.value, "dependencies": dependency_list},
                )
            ],
        )
        return task

    def claim_task(
        self,
        team_id: str,
        task_id: str,
        *,
        agent_id: str,
        base_sha: str,
        lease_seconds: int = 900,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_base(team, base_sha)
        require_identifier(agent_id, "agent_id")
        if agent_id not in team.members:
            raise AuthorizationError(f"Agent is not a durable team member: {agent_id}")
        if not 1 <= lease_seconds <= 86_400:
            raise ValidationError("lease_seconds must be between 1 and 86400")
        task = self._task(team, task_id)
        if not self._dependencies_done(team, task.dependencies):
            raise DependencyBlockedError(
                f"Task {task_id} is blocked by: {', '.join(self._unfinished_dependencies(team, task))}"
            )
        if task.status != TaskStatus.READY:
            raise TransitionError(f"Task {task_id} cannot be claimed from {task.status.value}")

        self._transition(task, TaskStatus.CLAIMED)
        task.attempt_number += 1
        task.attempt_id = f"{task.attempt_number}-{self.id_factory()}"
        task.lease_owner = agent_id
        task.lease_expires_at = self.clock() + lease_seconds
        task.updated_at = self.clock()
        self._save(
            team,
            [
                (
                    "task.claimed",
                    task_id,
                    agent_id,
                    {"attempt_id": task.attempt_id, "lease_expires_at": task.lease_expires_at},
                )
            ],
        )
        return task

    def start_task(
        self,
        team_id: str,
        task_id: str,
        *,
        attempt_id: str,
        base_sha: str,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_base(team, base_sha)
        task = self._task(team, task_id)
        self._require_attempt(task, attempt_id)
        self._transition(task, TaskStatus.RUNNING)
        task.updated_at = self.clock()
        self._save(
            team,
            [("task.started", task_id, task.lease_owner or "unknown", {"attempt_id": attempt_id})],
        )
        return task

    def renew_lease(
        self,
        team_id: str,
        task_id: str,
        *,
        attempt_id: str,
        base_sha: str,
        lease_seconds: int = 900,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_base(team, base_sha)
        if not 1 <= lease_seconds <= 86_400:
            raise ValidationError("lease_seconds must be between 1 and 86400")
        task = self._task(team, task_id)
        self._require_attempt(task, attempt_id)
        if task.status not in {TaskStatus.CLAIMED, TaskStatus.RUNNING}:
            raise TransitionError(f"Task {task_id} has no renewable lease in {task.status.value}")
        task.lease_expires_at = self.clock() + lease_seconds
        task.updated_at = self.clock()
        self._save(
            team,
            [
                (
                    "task.lease-renewed",
                    task_id,
                    task.lease_owner or "unknown",
                    {"attempt_id": attempt_id, "lease_expires_at": task.lease_expires_at},
                )
            ],
        )
        return task

    def validate_handoff(
        self,
        team_id: str,
        task_id: str,
        bundle: CompletionBundle,
    ) -> None:
        team = self._load_recovered(team_id)
        validate_completion_bundle(self._task(team, task_id), bundle)

    def complete_task(
        self,
        team_id: str,
        task_id: str,
        *,
        attempt_id: str,
        base_sha: str,
        bundle: CompletionBundle,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_base(team, base_sha)
        task = self._task(team, task_id)
        self._require_attempt(task, attempt_id)
        if task.status != TaskStatus.RUNNING:
            raise TransitionError(f"Task {task_id} must be RUNNING before completion")
        validate_completion_bundle(task, bundle)
        self._transition(task, TaskStatus.REVIEW)
        task.artifacts = bundle.artifacts
        task.test_evidence = bundle.test_evidence
        task.handoff = bundle.handoff
        task.updated_at = self.clock()
        self._save(
            team,
            [
                (
                    "task.submitted",
                    task_id,
                    task.lease_owner or "unknown",
                    {
                        "attempt_id": attempt_id,
                        "head_sha": bundle.handoff.head_sha,
                        "artifact_count": len(bundle.artifacts),
                    },
                )
            ],
        )
        return task

    def approve_task(
        self,
        team_id: str,
        task_id: str,
        *,
        actor_id: str,
        base_sha: str,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_captain(team, actor_id)
        self._require_base(team, base_sha)
        task = self._task(team, task_id)
        if task.status != TaskStatus.REVIEW:
            raise TransitionError(f"Task {task_id} must be REVIEW before approval")
        self._transition(task, TaskStatus.DONE)
        self._clear_lease(task)
        task.updated_at = self.clock()
        newly_ready = self._refresh_dependency_states(team)
        events: list[tuple[str, str | None, str, dict[str, Any]]] = [
            ("task.done", task_id, actor_id, {"head_sha": task.handoff.head_sha if task.handoff else None})
        ]
        events.extend(
            ("task.ready", ready_id, actor_id, {"reason": "dependencies completed"})
            for ready_id in newly_ready
        )
        self._save(team, events)
        return task

    def fail_task(
        self,
        team_id: str,
        task_id: str,
        *,
        attempt_id: str,
        base_sha: str,
        reason: str,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_base(team, base_sha)
        task = self._task(team, task_id)
        self._require_attempt(task, attempt_id)
        if task.status not in {TaskStatus.CLAIMED, TaskStatus.RUNNING}:
            raise TransitionError(f"Task {task_id} cannot fail from {task.status.value}")
        if not reason.strip():
            raise ValidationError("Failure reason must not be blank")
        self._transition(task, TaskStatus.FAILED)
        actor = task.lease_owner or "unknown"
        self._clear_lease(task)
        task.updated_at = self.clock()
        self._save(team, [("task.failed", task_id, actor, {"reason": reason.strip()})])
        return task

    def retry_task(
        self,
        team_id: str,
        task_id: str,
        *,
        actor_id: str,
        base_sha: str,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_captain(team, actor_id)
        self._require_base(team, base_sha)
        task = self._task(team, task_id)
        if task.status != TaskStatus.FAILED:
            raise TransitionError(f"Task {task_id} cannot retry from {task.status.value}")
        target = TaskStatus.READY if self._dependencies_done(team, task.dependencies) else TaskStatus.BLOCKED
        self._transition(task, target)
        task.artifacts = []
        task.test_evidence = []
        task.handoff = None
        task.updated_at = self.clock()
        self._save(team, [("task.retried", task_id, actor_id, {"status": target.value})])
        return task

    def cancel_task(
        self,
        team_id: str,
        task_id: str,
        *,
        actor_id: str,
        base_sha: str,
        reason: str,
    ) -> TaskRecord:
        team = self._load_recovered(team_id)
        self._require_captain(team, actor_id)
        self._require_base(team, base_sha)
        task = self._task(team, task_id)
        if not reason.strip():
            raise ValidationError("Cancellation reason must not be blank")
        self._transition(task, TaskStatus.CANCELLED)
        self._clear_lease(task)
        task.updated_at = self.clock()
        self._save(team, [("task.cancelled", task_id, actor_id, {"reason": reason.strip()})])
        return task

    def validate_changed_paths(self, team_id: str, task_id: str, paths: Iterable[str]) -> None:
        task = self._task(self._load_recovered(team_id), task_id)
        violations = ownership_violations(task, paths)
        if violations:
            raise ValidationError("Ownership validation failed: " + "; ".join(violations))

    def send_message(
        self,
        team_id: str,
        *,
        sender_id: str,
        recipient_id: str,
        body: str,
        task_id: str | None = None,
    ) -> MailboxMessage:
        team = self._load_recovered(team_id)
        require_identifier(sender_id, "sender_id")
        require_identifier(recipient_id, "recipient_id")
        if sender_id not in team.members:
            raise AuthorizationError(f"Sender is not a durable team member: {sender_id}")
        if recipient_id not in team.members:
            raise NotFoundError(f"Recipient is not a durable team member: {recipient_id}")
        if not body.strip() or len(body) > 20_000:
            raise ValidationError("Message body must contain 1-20000 characters")
        if task_id is not None:
            self._task(team, task_id)
        message = MailboxMessage(
            sequence=0,
            message_id=self.id_factory(),
            team_id=team_id,
            sender_id=sender_id,
            recipient_id=recipient_id,
            task_id=task_id,
            timestamp=self.clock(),
            body=body,
        )
        message = self.store.append_message(message)
        self._append_events(
            team_id,
            [
                (
                    "message.sent",
                    task_id,
                    sender_id,
                    {"message_id": message.message_id, "recipient_id": recipient_id},
                )
            ],
        )
        return message

    def read_mailbox(self, team_id: str, recipient_id: str, after_sequence: int = 0) -> list[dict[str, Any]]:
        self._load_recovered(team_id)
        return self.store.read_mailbox(team_id, recipient_id, after_sequence)

    def read_events(self, team_id: str, after_sequence: int = 0) -> list[dict[str, Any]]:
        self._load_recovered(team_id)
        return self.store.read_events(team_id, after_sequence)

    def _load_recovered(self, team_id: str) -> TeamRecord:
        team = self.store.load(team_id)
        validate_task_graph(team)
        now = self.clock()
        expired: list[tuple[str, str | None]] = []
        for task in team.tasks.values():
            if (
                task.status in {TaskStatus.CLAIMED, TaskStatus.RUNNING}
                and task.lease_expires_at is not None
                and task.lease_expires_at <= now
            ):
                expired.append((task.task_id, task.attempt_id))
                target = (
                    TaskStatus.READY
                    if self._dependencies_done(team, task.dependencies)
                    else TaskStatus.BLOCKED
                )
                self._transition(task, target)
                self._clear_lease(task)
                task.updated_at = now
        newly_ready = self._refresh_dependency_states(team)
        if expired or newly_ready:
            events = [
                (
                    "task.lease-expired",
                    task_id,
                    team.captain_id,
                    {"stale_attempt_id": attempt_id},
                )
                for task_id, attempt_id in expired
            ]
            events.extend(
                ("task.ready", task_id, team.captain_id, {"reason": "dependencies completed"})
                for task_id in newly_ready
            )
            self._save(team, events)
        return team

    def _refresh_dependency_states(self, team: TeamRecord) -> list[str]:
        newly_ready: list[str] = []
        for task in sorted(team.tasks.values(), key=lambda item: item.task_id):
            if task.status not in {TaskStatus.BLOCKED, TaskStatus.READY}:
                continue
            target = (
                TaskStatus.READY
                if self._dependencies_done(team, task.dependencies)
                else TaskStatus.BLOCKED
            )
            if task.status != target:
                self._transition(task, target)
                task.updated_at = self.clock()
                if target == TaskStatus.READY:
                    newly_ready.append(task.task_id)
        return newly_ready

    def _save(
        self,
        team: TeamRecord,
        events: list[tuple[str, str | None, str, dict[str, Any]]],
    ) -> None:
        expected_revision = team.revision
        team.revision += 1
        team.updated_at = self.clock()
        self.store.save(team, expected_revision)
        self._append_events(team.team_id, events)

    def _append_events(
        self,
        team_id: str,
        events: list[tuple[str, str | None, str, dict[str, Any]]],
    ) -> None:
        for event_type, task_id, actor_id, details in events:
            self.store.append_event(
                TeamEvent(
                    sequence=0,
                    event_id=self.id_factory(),
                    event_type=event_type,
                    team_id=team_id,
                    task_id=task_id,
                    actor_id=actor_id,
                    timestamp=self.clock(),
                    details=details,
                )
            )

    @staticmethod
    def _task(team: TeamRecord, task_id: str) -> TaskRecord:
        require_identifier(task_id, "task_id")
        try:
            return team.tasks[task_id]
        except KeyError as error:
            raise NotFoundError(f"Task not found: {task_id}") from error

    @staticmethod
    def _dependencies_done(team: TeamRecord, dependencies: Iterable[str]) -> bool:
        return all(team.tasks[dependency].status == TaskStatus.DONE for dependency in dependencies)

    @staticmethod
    def _unfinished_dependencies(team: TeamRecord, task: TaskRecord) -> list[str]:
        return sorted(
            dependency
            for dependency in task.dependencies
            if team.tasks[dependency].status != TaskStatus.DONE
        )

    @staticmethod
    def _require_captain(team: TeamRecord, actor_id: str) -> None:
        require_identifier(actor_id, "actor_id")
        if actor_id != team.captain_id:
            raise AuthorizationError(f"Only captain {team.captain_id} may perform this operation")

    @staticmethod
    def _require_base(team: TeamRecord, base_sha: str) -> None:
        require_sha(base_sha, "base_sha")
        if base_sha != team.base_sha:
            raise ValidationError("base_sha does not match the team's exact frozen SHA")

    def _require_attempt(self, task: TaskRecord, attempt_id: str) -> None:
        if not attempt_id or task.attempt_id != attempt_id:
            raise StaleAttemptError(
                f"Attempt {attempt_id!r} is stale; active attempt is {task.attempt_id!r}"
            )
        if task.lease_expires_at is None or task.lease_expires_at <= self.clock():
            raise StaleAttemptError(f"Attempt {attempt_id!r} has expired")

    @staticmethod
    def _transition(task: TaskRecord, target: TaskStatus) -> None:
        if target not in ALLOWED_TRANSITIONS[task.status]:
            raise TransitionError(
                f"Task {task.task_id} cannot transition {task.status.value} -> {target.value}"
            )
        task.status = target

    @staticmethod
    def _clear_lease(task: TaskRecord) -> None:
        task.attempt_id = None
        task.lease_owner = None
        task.lease_expires_at = None


def team_summary(team: TeamRecord) -> dict[str, Any]:
    counts = {status.value: 0 for status in TaskStatus}
    for task in team.tasks.values():
        counts[task.status.value] += 1
    return {
        "team_id": team.team_id,
        "name": team.name,
        "captain_id": team.captain_id,
        "base_sha": team.base_sha,
        "members": team.members,
        "revision": team.revision,
        "task_counts": counts,
        "tasks": [team.tasks[task_id].to_dict() for task_id in sorted(team.tasks)],
    }
