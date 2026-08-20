"""Versioned durable records for Cyclone development agent teams."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any


SCHEMA_VERSION = 1


def _object_list(data: dict[str, Any], key: str) -> list[dict[str, Any]]:
    value = data.get(key, [])
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise ValueError(f"{key} must be a list of objects")
    return value


def _string_list(data: dict[str, Any], key: str) -> list[str]:
    value = data.get(key, [])
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ValueError(f"{key} must be a list of strings")
    return value


def _object_map(data: dict[str, Any], key: str) -> dict[str, dict[str, Any]]:
    value = data.get(key, {})
    if not isinstance(value, dict) or any(
        not isinstance(item_key, str) or not isinstance(item, dict)
        for item_key, item in value.items()
    ):
        raise ValueError(f"{key} must be an object map")
    return value


class TaskStatus(str, Enum):
    BLOCKED = "BLOCKED"
    READY = "READY"
    CLAIMED = "CLAIMED"
    RUNNING = "RUNNING"
    REVIEW = "REVIEW"
    DONE = "DONE"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class TestResult(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    SKIPPED = "SKIPPED"


@dataclass(frozen=True)
class ArtifactRecord:
    path: str
    sha256: str
    description: str

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "ArtifactRecord":
        return cls(
            path=str(data.get("path", "")),
            sha256=str(data.get("sha256", "")),
            description=str(data.get("description", "")),
        )


@dataclass(frozen=True)
class TestEvidence:
    command: str
    result: TestResult
    summary: str

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "TestEvidence":
        return cls(
            command=str(data.get("command", "")),
            result=TestResult(str(data.get("result", ""))),
            summary=str(data.get("summary", "")),
        )


@dataclass(frozen=True)
class CommitEvidence:
    sha: str
    message: str

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "CommitEvidence":
        return cls(sha=str(data.get("sha", "")), message=str(data.get("message", "")))


@dataclass(frozen=True)
class HandoffRecord:
    branch: str
    base_sha: str
    head_sha: str
    commits: list[CommitEvidence]
    owned_scope_respected: bool
    files_changed: list[str]
    contract_changes: str
    tests_run: str
    ci_state: str
    physical_device_state: str
    security_privacy_notes: str
    known_limitations: str
    integration_instructions: str

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "HandoffRecord":
        return cls(
            branch=str(data.get("branch", "")),
            base_sha=str(data.get("base_sha", "")),
            head_sha=str(data.get("head_sha", "")),
            commits=[CommitEvidence.from_dict(item) for item in _object_list(data, "commits")],
            owned_scope_respected=data.get("owned_scope_respected") is True,
            files_changed=_string_list(data, "files_changed"),
            contract_changes=str(data.get("contract_changes", "")),
            tests_run=str(data.get("tests_run", "")),
            ci_state=str(data.get("ci_state", "")),
            physical_device_state=str(data.get("physical_device_state", "")),
            security_privacy_notes=str(data.get("security_privacy_notes", "")),
            known_limitations=str(data.get("known_limitations", "")),
            integration_instructions=str(data.get("integration_instructions", "")),
        )


@dataclass(frozen=True)
class CompletionBundle:
    artifacts: list[ArtifactRecord]
    test_evidence: list[TestEvidence]
    handoff: HandoffRecord

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "CompletionBundle":
        return cls(
            artifacts=[ArtifactRecord.from_dict(item) for item in _object_list(data, "artifacts")],
            test_evidence=[TestEvidence.from_dict(item) for item in _object_list(data, "test_evidence")],
            handoff=HandoffRecord.from_dict(
                data["handoff"] if isinstance(data.get("handoff"), dict) else _raise_handoff()
            ),
        )


@dataclass
class TaskRecord:
    team_id: str
    task_id: str
    parent_task: str | None
    owner_lane: str
    owned_paths: list[str]
    forbidden_paths: list[str]
    dependencies: list[str]
    base_sha: str
    attempt_id: str | None = None
    attempt_number: int = 0
    status: TaskStatus = TaskStatus.READY
    lease_owner: str | None = None
    lease_expires_at: int | None = None
    artifacts: list[ArtifactRecord] = field(default_factory=list)
    test_evidence: list[TestEvidence] = field(default_factory=list)
    handoff: HandoffRecord | None = None
    created_at: int = 0
    updated_at: int = 0

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["status"] = self.status.value
        data["test_evidence"] = [
            {**asdict(item), "result": item.result.value} for item in self.test_evidence
        ]
        return data

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "TaskRecord":
        handoff_data = data.get("handoff")
        return cls(
            team_id=str(data["team_id"]),
            task_id=str(data["task_id"]),
            parent_task=str(data["parent_task"]) if data.get("parent_task") is not None else None,
            owner_lane=str(data["owner_lane"]),
            owned_paths=_string_list(data, "owned_paths"),
            forbidden_paths=_string_list(data, "forbidden_paths"),
            dependencies=_string_list(data, "dependencies"),
            base_sha=str(data["base_sha"]),
            attempt_id=str(data["attempt_id"]) if data.get("attempt_id") is not None else None,
            attempt_number=int(data.get("attempt_number", 0)),
            status=TaskStatus(str(data["status"])),
            lease_owner=str(data["lease_owner"]) if data.get("lease_owner") is not None else None,
            lease_expires_at=(
                int(data["lease_expires_at"]) if data.get("lease_expires_at") is not None else None
            ),
            artifacts=[ArtifactRecord.from_dict(item) for item in _object_list(data, "artifacts")],
            test_evidence=[TestEvidence.from_dict(item) for item in _object_list(data, "test_evidence")],
            handoff=HandoffRecord.from_dict(handoff_data) if isinstance(handoff_data, dict) else None,
            created_at=int(data.get("created_at", 0)),
            updated_at=int(data.get("updated_at", 0)),
        )


@dataclass
class TeamRecord:
    team_id: str
    name: str
    captain_id: str
    base_sha: str
    tasks: dict[str, TaskRecord] = field(default_factory=dict)
    members: list[str] = field(default_factory=list)
    schema_version: int = SCHEMA_VERSION
    revision: int = 0
    created_at: int = 0
    updated_at: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "team_id": self.team_id,
            "name": self.name,
            "captain_id": self.captain_id,
            "base_sha": self.base_sha,
            "members": self.members,
            "revision": self.revision,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "tasks": {key: self.tasks[key].to_dict() for key in sorted(self.tasks)},
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "TeamRecord":
        version = int(data.get("schema_version", 0))
        if version != SCHEMA_VERSION:
            raise ValueError(f"Unsupported team schema version: {version}")
        tasks = {
            str(key): TaskRecord.from_dict(value)
            for key, value in _object_map(data, "tasks").items()
        }
        return cls(
            team_id=str(data["team_id"]),
            name=str(data["name"]),
            captain_id=str(data["captain_id"]),
            base_sha=str(data["base_sha"]),
            tasks=tasks,
            members=_string_list(data, "members"),
            schema_version=version,
            revision=int(data.get("revision", 0)),
            created_at=int(data.get("created_at", 0)),
            updated_at=int(data.get("updated_at", 0)),
        )


@dataclass(frozen=True)
class TeamEvent:
    sequence: int
    event_id: str
    event_type: str
    team_id: str
    task_id: str | None
    actor_id: str
    timestamp: int
    details: dict[str, Any]


@dataclass(frozen=True)
class MailboxMessage:
    sequence: int
    message_id: str
    team_id: str
    sender_id: str
    recipient_id: str
    task_id: str | None
    timestamp: int
    body: str


def _raise_handoff() -> dict[str, Any]:
    raise ValueError("handoff must be an object")
