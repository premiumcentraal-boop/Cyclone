"""Strict validation for SHAs, ownership, task DAGs, and handoffs."""

from __future__ import annotations

import re
from pathlib import PurePosixPath
from typing import Iterable

from .errors import ValidationError
from .models import CompletionBundle, TaskRecord, TaskStatus, TeamRecord, TestResult


SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def require_identifier(value: str, label: str) -> str:
    if not IDENTIFIER_PATTERN.fullmatch(value):
        raise ValidationError(f"{label} must be a simple 1-128 character identifier: {value!r}")
    return value


def require_sha(value: str, label: str = "SHA") -> str:
    if not SHA_PATTERN.fullmatch(value):
        raise ValidationError(f"{label} must be an exact lowercase 40-character Git SHA")
    return value


def normalize_path_pattern(value: str) -> str:
    raw = value.strip().replace("\\", "/")
    subtree = raw.endswith("/**")
    candidate = raw[:-3] if subtree else raw
    if not candidate or candidate.startswith("/") or re.match(r"^[A-Za-z]:", candidate):
        raise ValidationError(f"Path must be repository-relative: {value!r}")
    if any(character in candidate for character in "*?["):
        raise ValidationError("Only an optional terminal '/**' subtree marker is supported")
    path = PurePosixPath(candidate)
    if any(part in {"", ".", ".."} for part in path.parts):
        raise ValidationError(f"Path contains an unsafe segment: {value!r}")
    normalized = path.as_posix()
    return f"{normalized}/**" if subtree else normalized


def normalize_changed_path(value: str) -> str:
    normalized = normalize_path_pattern(value)
    if normalized.endswith("/**"):
        raise ValidationError("A changed file path cannot use a subtree marker")
    return normalized


def path_matches(pattern: str, path: str) -> bool:
    if pattern.endswith("/**"):
        prefix = pattern[:-3]
        return path == prefix or path.startswith(f"{prefix}/")
    return path == pattern


def validate_path_rules(owned_paths: Iterable[str], forbidden_paths: Iterable[str]) -> tuple[list[str], list[str]]:
    owned = [normalize_path_pattern(item) for item in owned_paths]
    forbidden = [normalize_path_pattern(item) for item in forbidden_paths]
    if not owned:
        raise ValidationError("At least one owned path is required")
    if len(set(owned)) != len(owned):
        raise ValidationError("Owned paths must be unique")
    if len(set(forbidden)) != len(forbidden):
        raise ValidationError("Forbidden paths must be unique")
    return sorted(owned), sorted(forbidden)


def ownership_violations(task: TaskRecord, paths: Iterable[str]) -> list[str]:
    violations: list[str] = []
    for raw_path in paths:
        path = normalize_changed_path(raw_path)
        if not any(path_matches(pattern, path) for pattern in task.owned_paths):
            violations.append(f"outside owned paths: {path}")
        if any(path_matches(pattern, path) for pattern in task.forbidden_paths):
            violations.append(f"matches forbidden path: {path}")
    return sorted(set(violations))


def validate_task_graph(team: TeamRecord) -> None:
    require_identifier(team.team_id, "team_id")
    require_identifier(team.captain_id, "captain_id")
    require_sha(team.base_sha, "team base_sha")
    if team.members != sorted(set(team.members)):
        raise ValidationError("Team members must be unique and sorted")
    for member_id in team.members:
        require_identifier(member_id, "member_id")
    if team.captain_id not in team.members:
        raise ValidationError("Team captain must be a durable member")
    task_ids = set(team.tasks)
    for key, task in team.tasks.items():
        require_identifier(task.task_id, "task_id")
        if key != task.task_id:
            raise ValidationError(f"Task map key does not match task_id: {key}")
        if task.team_id != team.team_id:
            raise ValidationError(f"Task {task.task_id} belongs to another team")
        if task.base_sha != team.base_sha:
            raise ValidationError(f"Task {task.task_id} does not use the team's frozen base SHA")
        owned, forbidden = validate_path_rules(task.owned_paths, task.forbidden_paths)
        if owned != task.owned_paths or forbidden != task.forbidden_paths:
            raise ValidationError(f"Task {task.task_id} path rules are not normalized")
        if len(set(task.dependencies)) != len(task.dependencies):
            raise ValidationError(f"Task {task.task_id} dependencies must be unique")
        if task.task_id in task.dependencies:
            raise ValidationError(f"Task {task.task_id} cannot depend on itself")
        missing = sorted(set(task.dependencies) - task_ids)
        if missing:
            raise ValidationError(f"Task {task.task_id} has missing dependencies: {', '.join(missing)}")
        if task.parent_task is not None and task.parent_task not in task_ids:
            raise ValidationError(f"Task {task.task_id} has missing parent {task.parent_task}")
        lease_active = task.status in {TaskStatus.CLAIMED, TaskStatus.RUNNING}
        if lease_active and not (task.attempt_id and task.lease_owner and task.lease_expires_at):
            raise ValidationError(f"Task {task.task_id} active state requires a complete lease")
        if task.status in {
            TaskStatus.BLOCKED,
            TaskStatus.READY,
            TaskStatus.DONE,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED,
        }:
            if task.attempt_id is not None or task.lease_owner is not None or task.lease_expires_at is not None:
                raise ValidationError(f"Task {task.task_id} inactive state cannot retain a lease")
        if task.status in {TaskStatus.REVIEW, TaskStatus.DONE}:
            if task.handoff is None:
                raise ValidationError(f"Task {task.task_id} {task.status.value} state requires a handoff")
            validate_completion_bundle(
                task,
                CompletionBundle(task.artifacts, task.test_evidence, task.handoff),
            )

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(task_id: str, path: list[str]) -> None:
        if task_id in visiting:
            start = path.index(task_id)
            cycle = path[start:] + [task_id]
            raise ValidationError(f"Task dependency cycle: {' -> '.join(cycle)}")
        if task_id in visited:
            return
        visiting.add(task_id)
        for dependency in sorted(team.tasks[task_id].dependencies):
            visit(dependency, path + [task_id])
        visiting.remove(task_id)
        visited.add(task_id)

    for task_id in sorted(task_ids):
        visit(task_id, [])


def validate_completion_bundle(task: TaskRecord, bundle: CompletionBundle) -> None:
    handoff = bundle.handoff
    require_sha(handoff.base_sha, "handoff base_sha")
    require_sha(handoff.head_sha, "handoff head_sha")
    if handoff.base_sha != task.base_sha:
        raise ValidationError("Handoff base_sha does not match the frozen task base_sha")
    if not handoff.branch.strip() or any(character.isspace() for character in handoff.branch):
        raise ValidationError("Handoff branch must be a non-blank name without whitespace")
    if not handoff.owned_scope_respected:
        raise ValidationError("Handoff must explicitly confirm owned scope was respected")
    if not handoff.commits:
        raise ValidationError("Handoff must include at least one commit")
    for commit in handoff.commits:
        require_sha(commit.sha, "commit SHA")
        if not commit.message.strip():
            raise ValidationError("Commit evidence must include a message")
    if handoff.commits[-1].sha != handoff.head_sha:
        raise ValidationError("The last handoff commit must equal head_sha")

    changed = [normalize_changed_path(item) for item in handoff.files_changed]
    if not changed or len(set(changed)) != len(changed):
        raise ValidationError("Handoff files_changed must be a non-empty unique list")
    violations = ownership_violations(task, changed)
    if violations:
        raise ValidationError("Ownership validation failed: " + "; ".join(violations))

    if not bundle.artifacts:
        raise ValidationError("Completion requires artifact evidence")
    artifact_paths: list[str] = []
    for artifact in bundle.artifacts:
        path = normalize_changed_path(artifact.path)
        artifact_paths.append(path)
        if not SHA256_PATTERN.fullmatch(artifact.sha256):
            raise ValidationError(f"Artifact {path} requires a lowercase SHA-256 digest")
        if not artifact.description.strip():
            raise ValidationError(f"Artifact {path} requires a description")
    if sorted(artifact_paths) != sorted(changed):
        raise ValidationError("Artifact paths must exactly match handoff files_changed")

    if not bundle.test_evidence:
        raise ValidationError("Completion requires test evidence")
    for evidence in bundle.test_evidence:
        if not evidence.command.strip() or not evidence.summary.strip():
            raise ValidationError("Test evidence requires command and summary")
        if evidence.result == TestResult.FAIL:
            raise ValidationError("Failing test evidence cannot complete a task")
    if not any(item.result == TestResult.PASS for item in bundle.test_evidence):
        raise ValidationError("At least one passing test is required")

    required_text = {
        "contract_changes": handoff.contract_changes,
        "tests_run": handoff.tests_run,
        "ci_state": handoff.ci_state,
        "physical_device_state": handoff.physical_device_state,
        "security_privacy_notes": handoff.security_privacy_notes,
        "known_limitations": handoff.known_limitations,
        "integration_instructions": handoff.integration_instructions,
    }
    missing = sorted(label for label, value in required_text.items() if not value.strip())
    if missing:
        raise ValidationError("Handoff fields must be explicit: " + ", ".join(missing))
