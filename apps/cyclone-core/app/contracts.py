"""Stable HTTP contracts for the Cyclone Desktop and internal adapters."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class HealthDependency(StrictModel):
    status: Literal["ok", "degraded", "unavailable", "unknown"]
    detail: str


class HealthResponse(StrictModel):
    status: Literal["ok", "degraded"]
    service: str = "cyclone-core"
    timestamp: datetime
    dependencies: dict[str, HealthDependency]


class AgentSummary(StrictModel):
    id: UUID
    slug: str
    name: str
    role: str
    description: str
    avatar_color: str
    avatar_shape: Literal["round", "blob", "squircle", "capsule", "triangle", "polygon", "cloud", "droplet", "diamond", "pebble"] | None = None
    status: str
    provider: str | None = None
    model: str | None = None
    hermes_profile: str
    workspace_path: str


class ComputerSessionResponse(StrictModel):
    id: str
    agent_id: str
    status: Literal["idle", "working", "waiting_for_user", "done", "error", "unavailable"]
    instruction: str | None = None
    stream_url: str | None = None
    recent_frame_url: str | None = None
    owner: Literal["agent", "human", "idle"]
    updated_at: datetime | None = None


class ComputerOwnershipRequest(StrictModel):
    owner: Literal["agent", "human", "idle"]


class CreateAgentRequest(StrictModel):
    slug: str = Field(min_length=1, max_length=63, pattern=r"^[a-z0-9][a-z0-9-]{0,62}$")
    name: str = Field(min_length=1, max_length=120)
    role: str = Field(default="", max_length=120)
    description: str = Field(default="", max_length=2_000)
    avatar_color: str = Field(default="#70B7A7", pattern=r"^#[0-9A-Fa-f]{6}$")
    avatar_shape: Literal["round", "blob", "squircle", "capsule", "triangle", "polygon", "cloud", "droplet"] | None = None
    provider: str | None = Field(default=None, max_length=128)
    model: str | None = Field(default=None, max_length=256)
    hermes_profile: str = Field(default="default", max_length=128)
    workspace_path: str = Field(default="/workspace", max_length=512)


class ConversationSummary(StrictModel):
    id: UUID
    title: str
    kind: str
    project_key: str | None = None
    updated_at: datetime
    latest_preview: str | None = None


class ConversationMember(StrictModel):
    display_name: str
    member_type: Literal["agent", "human", "system"]
    member_role: str
    agent: AgentSummary | None = None


class Mention(StrictModel):
    id: UUID
    mention_type: Literal["agent", "group", "everyone", "routine", "connector"]
    target_agent_id: UUID | None = None
    target_slug: str | None = None
    position_start: int | None = None
    position_end: int | None = None


class Message(StrictModel):
    id: UUID
    conversation_id: UUID
    task_id: UUID | None = None
    reply_to_message_id: UUID | None = None
    author_type: Literal["human", "agent", "system", "automation"]
    author_agent_id: UUID | None = None
    author_name: str
    kind: Literal["message", "activity", "task", "handoff", "approval", "result", "automation", "system"]
    body: str
    metadata: dict[str, Any]
    source: str
    mentions: list[Mention] = Field(default_factory=list)
    created_at: datetime


class ConversationDetail(StrictModel):
    id: UUID
    title: str
    kind: str
    project_key: str | None = None
    hermes_conversation_key: str | None = None
    created_at: datetime
    updated_at: datetime
    members: list[ConversationMember]
    messages: list[Message]


class CreateConversationRequest(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    kind: Literal["direct", "group", "cluster", "routine"] = "direct"
    project_key: str | None = Field(default=None, max_length=120)
    agent_slugs: list[str] = Field(min_length=1, max_length=20)


class CreateMessageRequest(StrictModel):
    body: str = Field(min_length=1, max_length=20_000)
    agent_slug: str = Field(default="chief", min_length=1, max_length=63)
    run: bool = True
    provider: str | None = Field(default=None, max_length=128)
    model: str | None = Field(default=None, max_length=256)
    reply_to_message_id: UUID | None = None


class InboxItem(StrictModel):
    id: UUID
    agent_id: UUID
    event_type: str
    conversation_id: UUID | None = None
    message_id: UUID | None = None
    task_id: UUID | None = None
    source_agent_id: UUID | None = None
    payload: dict[str, Any] = Field(default_factory=dict)
    status: str
    attempts: int
    created_at: datetime
    delivered_at: datetime | None = None


class ReactionResponse(StrictModel):
    id: UUID
    message_id: UUID
    actor_type: Literal["human", "agent"]
    actor_agent_id: UUID | None = None
    emoji: str
    created_at: datetime


class ReactionRequest(StrictModel):
    emoji: str = Field(min_length=1, max_length=8)
    actor_type: Literal["human", "agent"] = "human"
    actor_agent_id: UUID | None = None


class UserRecord(StrictModel):
    id: UUID
    display_name: str
    initials: str
    telegram_chat_id: int | None = None
    created_at: datetime
    updated_at: datetime


class TaskStatusRequest(StrictModel):
    status: Literal["queued", "running", "awaiting_review", "changes_requested", "completed", "blocked", "failed", "cancelled"]
    note: str | None = Field(default=None, max_length=2_000)


class RunApprovalRequest(StrictModel):
    choice: Literal["once", "session", "always", "deny"]


class IntegrationState(StrictModel):
    name: str
    available: bool
    detail: str


class IntegrationResponse(StrictModel):
    integrations: list[IntegrationState]


class MemberAddRequest(StrictModel):
    agent_slug: str = Field(min_length=1, max_length=63)


class ArtifactResponse(StrictModel):
    id: UUID
    task_id: UUID | None = None
    conversation_id: UUID | None = None
    created_by_agent_id: UUID | None = None
    type: str
    path: str
    created_at: datetime


class ArtifactCreateRequest(StrictModel):
    type: str = Field(default="file", max_length=64)
    path: str = Field(min_length=1, max_length=1_000)
    created_by_agent_slug: str | None = None


class TaskSummary(StrictModel):
    id: UUID
    conversation_id: UUID
    parent_task_id: UUID | None = None
    owner_agent_id: UUID | None = None
    title: str
    objective: str
    status: str
    priority: int
    hermes_run_id: str | None = None
    result_summary: str | None = None
    verification_criteria: str | None = None
    created_at: datetime
    started_at: datetime | None = None
    completed_at: datetime | None = None
    updated_at: datetime


class ApprovalRequest(StrictModel):
    capability: str = Field(min_length=1, max_length=128)
    target: str = Field(min_length=1, max_length=500)
    scope: dict[str, Any] = Field(default_factory=dict)
    expected_effect: str = Field(min_length=1, max_length=2_000)
    policy_reason: str = Field(min_length=1, max_length=2_000)
    task_id: UUID | None = None
    requested_by_agent_slug: str | None = None
    expires_at: datetime | None = None


class Approval(StrictModel):
    id: UUID
    conversation_id: UUID
    task_id: UUID | None = None
    requested_by_agent_id: UUID | None = None
    capability: str
    target: str
    scope: dict[str, Any]
    expected_effect: str
    policy_reason: str
    status: str
    decided_by: str | None = None
    decided_at: datetime | None = None
    expires_at: datetime | None = None
    created_at: datetime


class ApprovalDecisionRequest(StrictModel):
    decision: Literal["approved", "denied"]
    decided_by: str = Field(default="administrator", min_length=1, max_length=120)


class AutomationEventRequest(StrictModel):
    external_event_id: str = Field(min_length=1, max_length=255)
    event_type: str = Field(min_length=1, max_length=128)
    routine_slug: str | None = Field(default=None, max_length=63)
    conversation_id: UUID | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


class AutomationEventResponse(StrictModel):
    id: UUID
    status: Literal["received", "duplicate"]
    conversation_id: UUID | None = None


class HermesRunStart(StrictModel):
    run_id: str
    status: str


class HermesRunStatus(StrictModel):
    run_id: str
    status: str
    output: str | None = None
    raw: dict[str, Any] = Field(default_factory=dict)


class AgentRunResponse(StrictModel):
    task: TaskSummary
    user_message: Message | None = None
    run: HermesRunStart | None = None
    status: Literal["queued", "started", "blocked"]
    detail: str


class HermesRunEvent(StrictModel):
    event: str
    data: dict[str, Any]


class HermesRunEventsResponse(StrictModel):
    run_id: str
    events: list[HermesRunEvent]


class EventEnvelope(StrictModel):
    id: str
    type: str
    conversation_id: UUID
    occurred_at: datetime
    payload: dict[str, Any]


class MemoryWriteRequest(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    category: Literal["Agents", "Projects", "People", "Research", "Decisions", "Knowledge", "Routines", "Sessions", "Tasks", "Skills", "System", "Inbox", "Archive"]
    content: str = Field(min_length=1, max_length=50_000)
    project_key: str | None = Field(default=None, max_length=120)
    agent_slug: str | None = Field(default=None, max_length=63)


class MemoryEntry(StrictModel):
    id: UUID
    vault_path: str
    title: str
    category: str
    project_key: str | None = None
    agent_id: UUID | None = None
    created_at: datetime
    updated_at: datetime


class MemorySearchResponse(StrictModel):
    entries: list[MemoryEntry]
    retrieval: Literal["keyword"]
    query: str


class HostBridgeRequest(StrictModel):
    capability: Literal[
        "filesystem.read", "filesystem.write", "app.launch", "process.list",
        "powershell.execute", "git.execute", "browser.open", "browser.navigate",
        "screenshot.capture", "window.list",
    ]
    target: str = Field(min_length=1, max_length=1_000)
    arguments: dict[str, Any] = Field(default_factory=dict)
    conversation_id: UUID
    task_id: UUID | None = None


class HostBridgeDecision(StrictModel):
    allowed: bool
    requires_approval: bool
    reason: str
    approval: Approval | None = None


class ErrorResponse(StrictModel):
    detail: str
    code: str
