"""Unit tests for the deterministic group coordinator (routing rules)."""

from datetime import datetime, timezone
from uuid import uuid4

from app.contracts import AgentSummary, ConversationDetail, TaskSummary
from app.router import route_group_message


def _agent(slug: str, name: str, role: str = "") -> AgentSummary:
    return AgentSummary(
        id=uuid4(), slug=slug, name=name, role=role, description="", avatar_color="#70B7A7",
        avatar_shape="round", status="idle", provider=None, model=None,
        hermes_profile="default", workspace_path="/workspace",
    )


def _conversation(kind: str = "group") -> ConversationDetail:
    return ConversationDetail(
        id=uuid4(), title="Crew", kind=kind, project_key=None, hermes_conversation_key=None,
        created_at=datetime.now(timezone.utc), updated_at=datetime.now(timezone.utc),
        members=[], messages=[],
    )


def test_addressed_mention_wins() -> None:
    chief, research, writer = _agent("chief", "Chief"), _agent("research", "Research"), _agent("writer", "Writer")
    decision = route_group_message(
        conversation=_conversation(),
        requested_agent=chief,
        member_agents=[chief, research, writer],
        addressed_slug="writer",
        mentioned_slugs=["research", "writer"],
        has_everyone=False,
        active_task=None,
        last_agent_message_author_id=None,
    )
    assert decision.target is writer
    assert not decision.broadcast


def test_inline_mention_routes_to_first_mentioned_member() -> None:
    chief, research, writer = _agent("chief", "Chief"), _agent("research", "Research"), _agent("writer", "Writer")
    decision = route_group_message(
        conversation=_conversation(),
        requested_agent=chief,
        member_agents=[chief, research, writer],
        addressed_slug=None,
        mentioned_slugs=["writer", "research"],
        has_everyone=False,
        active_task=None,
        last_agent_message_author_id=None,
    )
    assert decision.target is writer
    assert decision.reason == "mentioned @writer"


def test_everyone_is_a_broadcast_not_a_single_owner() -> None:
    chief, research = _agent("chief", "Chief"), _agent("research", "Research")
    decision = route_group_message(
        conversation=_conversation(),
        requested_agent=chief,
        member_agents=[chief, research],
        addressed_slug=None,
        mentioned_slugs=["research"],
        has_everyone=True,
        active_task=None,
        last_agent_message_author_id=None,
    )
    assert decision.broadcast is True
    assert decision.target is None


def test_active_task_owner_gets_unmentioned_messages() -> None:
    chief, developer = _agent("chief", "Chief"), _agent("developer", "Developer")
    task = TaskSummary(
        id=uuid4(), conversation_id=uuid4(), parent_task_id=None, owner_agent_id=developer.id,
        title="Build", objective="Build", status="running", priority=0, hermes_run_id=None,
        result_summary=None, verification_criteria=None,
        created_at=datetime.now(timezone.utc), started_at=None, completed_at=None, updated_at=datetime.now(timezone.utc),
    )
    decision = route_group_message(
        conversation=_conversation(),
        requested_agent=chief,
        member_agents=[chief, developer],
        addressed_slug=None,
        mentioned_slugs=[],
        has_everyone=False,
        active_task=task,
        last_agent_message_author_id=None,
    )
    assert decision.target is developer
    assert decision.reason == f"owns active task {task.id}"


def test_last_speaker_continues_otherwise() -> None:
    chief, research = _agent("chief", "Chief"), _agent("research", "Research")
    decision = route_group_message(
        conversation=_conversation(),
        requested_agent=chief,
        member_agents=[chief, research],
        addressed_slug=None,
        mentioned_slugs=[],
        has_everyone=False,
        active_task=None,
        last_agent_message_author_id=str(research.id),
    )
    assert decision.target is research
    assert decision.reason == "last agent speaker"


def test_coordinator_defaults_when_no_signal() -> None:
    chief, research = _agent("chief", "Chief"), _agent("research", "Research")
    decision = route_group_message(
        conversation=_conversation(),
        requested_agent=chief,
        member_agents=[chief, research],
        addressed_slug=None,
        mentioned_slugs=[],
        has_everyone=False,
        active_task=None,
        last_agent_message_author_id=None,
    )
    assert decision.target is chief
    assert decision.reason == "group coordinator"


def test_falls_back_to_first_member_without_coordinator() -> None:
    research = _agent("research", "Research")
    writer = _agent("writer", "Writer")
    decision = route_group_message(
        conversation=_conversation(),
        requested_agent=research,
        member_agents=[research, writer],
        addressed_slug=None,
        mentioned_slugs=[],
        has_everyone=False,
        active_task=None,
        last_agent_message_author_id=None,
    )
    assert decision.target is research
