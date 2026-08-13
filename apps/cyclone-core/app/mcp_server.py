"""Cyclone MCP server: first-class collaboration tools for Hermes agents.

Persistent Cyclone agents run inside Hermes. Through this server they can
genuinely message teammates, hand off work, post results, and read group
context — without the human relaying anything and without string-parsing
fakes.

The server is mounted at /mcp on Cyclone Core (loopback-bound like the rest
of the API). Hermes connects with ``hermes mcp add cyclone --url
http://cyclone-core:8787/mcp``; the gateway auto-reloads MCP connections when
the config changes.

Tools identify the caller by the agent slug the prompt instructs them to
pass. This is a single-user localhost product; cross-container authentication
is out of scope and documented as such.
"""

from __future__ import annotations

from typing import Any, Callable
from uuid import UUID

from .orchestrator import build_context_packet, publish_message, start_agent_run, wake_agent
from .repository import NotFoundError

ServicesGetter = Callable[[], Any]


def _agent_by_slug(runtime: Any, slug: str) -> Any:
    try:
        return runtime.repository.get_agent_by_slug(slug)
    except NotFoundError as error:
        raise ValueError(f"Unknown agent slug: {slug}") from error


def _conversation(runtime: Any, conversation_id: str) -> Any:
    try:
        return runtime.repository.get_conversation(UUID(conversation_id), message_limit=100)
    except (NotFoundError, ValueError) as error:
        raise ValueError(f"Unknown conversation: {conversation_id}") from error


def _member_agents(conversation: Any) -> list[Any]:
    return [member.agent for member in conversation.members if member.agent is not None]


def _find_direct_conversation(runtime: Any, first: Any, second: Any) -> Any:
    """Find or create the direct conversation between two agents."""
    try:
        conversations = runtime.repository.list_conversations()
    except Exception:
        conversations = []
    for summary in conversations:
        if summary.kind != "direct":
            continue
        detail = runtime.repository.get_conversation(summary.id, message_limit=1)
        slugs = {m.agent.slug for m in detail.members if m.agent}
        if slugs == {first.slug, second.slug}:
            return detail
    created = runtime.repository.create_conversation(
        title=f"{first.name} & {second.name}",
        kind="direct",
        project_key=None,
        agent_slugs=[first.slug, second.slug],
    )
    return created


async def _post_agent_message(
    runtime: Any,
    *,
    conversation: Any,
    from_agent: Any,
    body: str,
    task_id: str | None = None,
    kind: str = "message",
    reply_to_message_id: str | None = None,
) -> Any:
    message = await runtime.repository.add_message(
        conversation_id=conversation.id,
        author_type="agent",
        author_agent_id=from_agent.id,
        kind=kind,
        body=body,
        task_id=UUID(task_id) if task_id else None,
        source="hermes",
        reply_to_message_id=UUID(reply_to_message_id) if reply_to_message_id else None,
    )
    await publish_message(runtime, message, "message.created")
    return message


def create_cyclone_mcp(get_services: ServicesGetter) -> Any:
    """Build the FastMCP application exposing Cyclone collaboration tools."""
    try:
        from mcp.server.fastmcp import FastMCP
    except ImportError as error:  # pragma: no cover - host test preflight
        raise RuntimeError("The mcp package is required for the Cyclone MCP server.") from error

    mcp = FastMCP("cyclone-collaboration")

    @mcp.tool()
    async def send_agent_message(
        from_agent_slug: str,
        to_agent_slug: str,
        message: str,
        conversation_id: str | None = None,
        task_id: str | None = None,
        handoff: bool = False,
        acceptance_criteria: str | None = None,
    ) -> str:
        """Send a real message from one Cyclone agent to another.

        When handoff=True the message additionally becomes a task handoff:
        the receiving agent gets a new owned task in its inbox and is woken.
        Use handoff=True when you are transferring ownership of work.
        """
        runtime = get_services()
        sender = _agent_by_slug(runtime, from_agent_slug)
        receiver = _agent_by_slug(runtime, to_agent_slug)
        if sender.id == receiver.id:
            raise ValueError("Cannot message yourself; post to the group instead.")
        if conversation_id:
            conversation = _conversation(runtime, conversation_id)
            if not any(m.agent and m.agent.id == sender.id for m in conversation.members):
                raise ValueError(f"{sender.name} is not a member of that conversation.")
        else:
            conversation = _find_direct_conversation(runtime, sender, receiver)

        member_agents = _member_agents(conversation)
        posted = await _post_agent_message(
            runtime, conversation=conversation, from_agent=sender, body=message, task_id=task_id
        )
        if handoff:
            parent = None
            if task_id:
                parent = await runtime.repository.get_task(UUID(task_id))
            child_task = await runtime.repository.create_task(
                conversation_id=conversation.id,
                owner_agent_id=receiver.id,
                title=message[:300],
                objective=message,
                parent_task_id=parent.id if parent else None,
                verification_criteria=acceptance_criteria,
            )
            await runtime.repository.create_handoff(
                task_id=parent.id if parent else child_task.id,
                from_agent_id=sender.id,
                to_agent_id=receiver.id,
                summary=message,
                acceptance_criteria=acceptance_criteria,
            )
            handoff_message = await runtime.repository.add_message(
                conversation_id=conversation.id,
                task_id=child_task.id,
                author_type="system",
                kind="handoff",
                body=f"{sender.name} handed work to {receiver.name}: {message}",
                metadata={"from_slug": sender.slug, "to_slug": receiver.slug, "task_id": str(child_task.id)},
            )
            await publish_message(runtime, handoff_message, "handoff.created")
            await wake_agent(
                runtime,
                agent=receiver,
                conversation=conversation,
                member_agents=member_agents,
                task=child_task,
                source_agent_id=sender.id,
                event_type="handoff.received",
                payload={"input_text": message, "extra": f"@{sender.slug} handed you this task:\n{message}"},
            )
            return f"Handoff delivered to @{receiver.slug} (task {child_task.id})."
        await wake_agent(
            runtime,
            agent=receiver,
            conversation=conversation,
            member_agents=member_agents,
            trigger_message=posted,
            source_agent_id=sender.id,
            event_type="agent.message",
            payload={"input_text": message},
        )
        return f"Message delivered to @{receiver.slug}."

    @mcp.tool()
    async def send_group_message(
        agent_slug: str,
        conversation_id: str,
        message: str,
        mention_agent_slugs: list[str] | None = None,
        task_id: str | None = None,
    ) -> str:
        """Post a message into a group conversation as the calling agent.

        mention_agent_slugs wakes the named teammates (they receive your
        message in their inbox); omit it for a plain group update.
        """
        runtime = get_services()
        sender = _agent_by_slug(runtime, agent_slug)
        conversation = _conversation(runtime, conversation_id)
        posted = await _post_agent_message(
            runtime, conversation=conversation, from_agent=sender, body=message, task_id=task_id
        )
        member_agents = _member_agents(conversation)
        by_slug = {member.slug: member for member in member_agents}
        woken: list[str] = []
        for slug in mention_agent_slugs or []:
            target = by_slug.get(slug)
            if target is None or target.id == sender.id:
                continue
            await runtime.repository.add_mentions(posted.id, [{
                "mention_type": "agent",
                "target_agent_id": target.id,
                "target_slug": target.slug,
            }])
            await wake_agent(
                runtime,
                agent=target,
                conversation=conversation,
                member_agents=member_agents,
                trigger_message=posted,
                source_agent_id=sender.id,
                event_type="group.mention",
                payload={"input_text": message},
            )
            woken.append(slug)
        return f"Posted to the group. Woken: {', '.join('@' + slug for slug in woken) or 'nobody'}."

    @mcp.tool()
    async def handoff_task(
        from_agent_slug: str,
        to_agent_slug: str,
        conversation_id: str,
        summary: str,
        task_id: str | None = None,
        acceptance_criteria: str | None = None,
    ) -> str:
        """Transfer ownership of work to another agent: durable handoff record,
        chat handoff event, new owned task, and a wake for the receiver."""
        runtime = get_services()
        sender = _agent_by_slug(runtime, from_agent_slug)
        receiver = _agent_by_slug(runtime, to_agent_slug)
        conversation = _conversation(runtime, conversation_id)
        member_agents = _member_agents(conversation)
        parent = await runtime.repository.get_task(UUID(task_id)) if task_id else None
        child_task = await runtime.repository.create_task(
            conversation_id=conversation.id,
            owner_agent_id=receiver.id,
            title=summary[:300],
            objective=summary,
            parent_task_id=parent.id if parent else None,
            verification_criteria=acceptance_criteria,
        )
        await runtime.repository.create_handoff(
            task_id=parent.id if parent else child_task.id,
            from_agent_id=sender.id,
            to_agent_id=receiver.id,
            summary=summary,
            acceptance_criteria=acceptance_criteria,
        )
        handoff_message = await runtime.repository.add_message(
            conversation_id=conversation.id,
            task_id=child_task.id,
            author_type="system",
            kind="handoff",
            body=f"{sender.name} handed work to {receiver.name}: {summary}",
            metadata={"from_slug": sender.slug, "to_slug": receiver.slug, "task_id": str(child_task.id)},
        )
        await publish_message(runtime, handoff_message, "handoff.created")
        await wake_agent(
            runtime,
            agent=receiver,
            conversation=conversation,
            member_agents=member_agents,
            task=child_task,
            source_agent_id=sender.id,
            event_type="handoff.received",
            payload={"input_text": summary, "extra": f"@{sender.slug} handed you this task:\n{summary}"},
        )
        return f"Task handed to @{receiver.slug} (task {child_task.id})."

    @mcp.tool()
    async def post_result(agent_slug: str, conversation_id: str, task_id: str, summary: str, status: str = "completed") -> str:
        """Post your completed result into the group as yourself and update the task."""
        runtime = get_services()
        agent = _agent_by_slug(runtime, agent_slug)
        conversation = _conversation(runtime, conversation_id)
        task = await runtime.repository.get_task(UUID(task_id))
        if task.owner_agent_id != agent.id:
            raise ValueError("You can only complete tasks you own.")
        message = await _post_agent_message(
            runtime, conversation=conversation, from_agent=agent, body=summary, task_id=task_id, kind="result"
        )
        terminal = await runtime.repository.set_task_terminal(task.id, status=status if status in ("completed", "failed", "cancelled") else "completed", result_summary=summary)
        await runtime.event_bus.publish(
            conversation_id=conversation.id, event_type="task.updated", payload=terminal.model_dump(mode="json")
        )
        return f"Result posted (task {task.id} -> {terminal.status})."

    @mcp.tool()
    async def update_task_status(agent_slug: str, task_id: str, status: str, note: str | None = None) -> str:
        """Transition the task state machine (review loop): e.g. running ->
        awaiting_review -> changes_requested -> running -> completed."""
        runtime = get_services()
        agent = _agent_by_slug(runtime, agent_slug)
        task = await runtime.repository.get_task(UUID(task_id))
        if task.owner_agent_id != agent.id and status not in ("changes_requested", "awaiting_review"):
            raise ValueError("You can only transition tasks you own (reviews may be requested by the reviewer).")
        allowed = {"queued", "running", "awaiting_review", "changes_requested", "completed", "blocked", "failed", "cancelled"}
        if status not in allowed:
            raise ValueError(f"Unknown status: {status}")
        updated = await runtime.repository.set_task_status(task.id, status)
        conversation = await runtime.repository.get_conversation(task.conversation_id, message_limit=1)
        body = f"{agent.name} set task status to {status}." + (f" {note}" if note else "")
        system_message = await runtime.repository.add_message(
            conversation_id=task.conversation_id,
            task_id=task.id,
            author_type="system",
            kind="task",
            body=body,
            metadata={"task_id": str(task.id), "status": status, "note": note},
        )
        await publish_message(runtime, system_message, "task.updated")
        await runtime.event_bus.publish(
            conversation_id=conversation.id, event_type="task.updated", payload=updated.model_dump(mode="json")
        )
        return f"Task {task.id} is now {status}."

    @mcp.tool()
    async def get_group_context(agent_slug: str, conversation_id: str, limit: int = 8) -> str:
        """Read the recent group conversation and task state (context on demand)."""
        runtime = get_services()
        agent = _agent_by_slug(runtime, agent_slug)
        conversation = _conversation(runtime, conversation_id)
        member_agents = _member_agents(conversation)
        recent = [m for m in conversation.messages if m.kind in ("message", "result", "handoff")][-limit:]
        lines = [f"Group: {conversation.title}", f"Members: {', '.join(m.name for m in member_agents)}"]
        for message in recent:
            name = "You" if message.author_type == "human" else message.author_name
            lines.append(f"{name}: {message.body[:400]}")
        active = await runtime.repository.get_active_task(conversation.id)
        if active:
            lines.append(f"Active task: {active.title} (status {active.status}, owner {active.owner_agent_id})")
        return "\n".join(lines)

    @mcp.tool()
    async def get_my_inbox(agent_slug: str, limit: int = 10) -> str:
        """List your pending Cyclone inbox items (work assigned to you)."""
        runtime = get_services()
        agent = _agent_by_slug(runtime, agent_slug)
        items = await runtime.repository.list_agent_inbox(agent.id, limit=limit, only_pending=True)
        if not items:
            return "Your inbox is empty."
        lines = ["Pending inbox items:"]
        for item in items:
            lines.append(f"- [{item.event_type}] {item.payload.get('input_text', '')[:200]} (created {item.created_at.isoformat()})")
        return "\n".join(lines)

    @mcp.tool()
    async def register_artifact(agent_slug: str, task_id: str, path: str, type: str = "file") -> str:
        """Register a file/artifact reference against a task so handoffs carry
        the real path, not a vague summary."""
        runtime = get_services()
        agent = _agent_by_slug(runtime, agent_slug)
        task = await runtime.repository.get_task(UUID(task_id))
        artifact = await runtime.repository.create_artifact(
            task_id=task.id,
            conversation_id=task.conversation_id,
            created_by_agent_id=agent.id,
            type_=type,
            path=path,
        )
        return f"Artifact registered: {path} (id {artifact.id})"

    return mcp
