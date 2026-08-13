"""Cyclone orchestration: waking persistent agents, dispatching inbox items,
monitoring Hermes runs, and executing handoffs.

This module is the collaboration engine above Hermes. HTTP routes and the
Cyclone MCP server both call into it, so agents contacted through either path
behave identically: durable inbox item -> context packet -> real Hermes run ->
result posted by the agent itself.
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path
from typing import Any, AsyncIterator
from uuid import UUID

from .contracts import AgentRunResponse, AgentSummary, HermesRunStart, Message, TaskSummary
from .context import build_agent_context
from .mentions import HandoffInstruction, parse_handoffs, parse_mentions

MAX_HANDOFF_DEPTH = 4  # Delegation-loop guard per task ancestry chain.


async def publish_message(runtime: Any, message: object, event_type: str = "message.created") -> None:
    payload = message.model_dump(mode="json")
    await runtime.event_bus.publish(
        conversation_id=UUID(str(payload["conversation_id"])), event_type=event_type, payload=payload
    )


async def _read_vault_excerpt(runtime: Any, vault_path: str, limit: int = 600) -> str | None:
    """Return a short readable excerpt of a vault note, or None when unreadable."""
    try:
        path = Path(vault_path)
        if not path.is_absolute():
            path = runtime.settings.vault_path / path
        text = path.read_text(encoding="utf-8", errors="replace")[:limit]
        return " ".join(text.split())
    except OSError:
        return None


async def build_context_packet(
    runtime: Any,
    *,
    agent: AgentSummary,
    conversation: Any,
    member_agents: list[AgentSummary],
    trigger_message: Message | None = None,
    task: TaskSummary | None = None,
    extra: str | None = None,
) -> str:
    """Assemble the focused context packet for one agent turn."""
    reply_message: Message | None = None
    if trigger_message is not None and trigger_message.reply_to_message_id:
        for message in conversation.messages:
            if message.id == trigger_message.reply_to_message_id:
                reply_message = message
                break
    recent = [m for m in conversation.messages if m.kind in ("message", "result", "handoff")][-8:]

    knowledge: list[dict[str, Any]] = []
    try:
        query = (trigger_message.body if trigger_message else task.title if task else "")[:300]
        if query:
            knowledge = await runtime.repository.search_knowledge(query, limit=3)
    except Exception:
        knowledge = []

    if knowledge:
        for entry in knowledge:
            excerpt = await _read_vault_excerpt(runtime, entry["vault_path"])
            if excerpt:
                entry = dict(entry)
                entry["excerpt"] = excerpt
        knowledge = [entry for entry in knowledge]

    return build_agent_context(
        agent=agent,
        member_agents=member_agents,
        trigger_message=trigger_message,
        reply_to_message=reply_message,
        task=task,
        recent_messages=recent,
        knowledge_entries=knowledge,
        extra=extra,
    )


async def _monitor_run(
    runtime: Any,
    *,
    task_id: UUID,
    conversation_id: UUID,
    agent: AgentSummary,
    run_id: str,
    member_agents: list[AgentSummary],
    inbox_item_id: UUID | None = None,
) -> None:
    """Poll bounded Hermes run status; durable task/message records are truth."""
    try:
        waited = 0
        for _ in range(600):
            await asyncio.sleep(1)
            current = await runtime.hermes.get_run(run_id)
            run_status = str(current.get("status", "unknown"))
            if run_status == "waiting_for_approval":
                # A run paused for a human decision must not age out: it only
                # resolves when the operator answers (desktop card or Telegram).
                waited = 0
                await _handle_run_approval(runtime, run_id=run_id, task_id=task_id, conversation_id=conversation_id, agent=agent)
                continue
            waited += 1
            if run_status in {"completed", "failed", "cancelled"}:
                output = current.get("output")
                summary = output if isinstance(output, str) else None
                mapped_status = "completed" if run_status == "completed" else "failed" if run_status == "failed" else "cancelled"
                # Hermes completing a run means the work is ready to inspect,
                # not that Cyclone has independently verified it. A durable
                # reviewer decision is the only path from awaiting_review to
                # completed.
                if mapped_status == "completed":
                    task = await runtime.repository.set_task_awaiting_review(task_id, summary)
                    await runtime.repository.set_agent_status(agent.id, "idle")
                else:
                    task = await runtime.repository.set_task_terminal(task_id, status=mapped_status, result_summary=summary)
                    await runtime.repository.set_agent_status(agent.id, "error")
                result_body = summary or (
                    "The agent finished without a shareable summary."
                    if mapped_status == "completed"
                    else "The agent run ended before it could provide a shareable summary."
                )
                metadata: dict[str, object] = {"hermes_run_id": run_id, "status": run_status}
                if mapped_status == "completed" and summary:
                    mention_slugs = parse_mentions(summary)
                    if mention_slugs:
                        metadata["mentions"] = mention_slugs
                message = await runtime.repository.add_message(
                    conversation_id=conversation_id,
                    task_id=task_id,
                    author_type="agent",
                    author_agent_id=agent.id,
                    kind="result" if mapped_status == "completed" else "activity",
                    body=result_body,
                    metadata=metadata,
                    source="hermes",
                )
                await publish_message(runtime, message, "agent.run.completed")
                await runtime.event_bus.publish(
                    conversation_id=conversation_id,
                    event_type="task.updated",
                    payload=task.model_dump(mode="json"),
                )
                if mapped_status == "completed":
                    review_message = await runtime.repository.add_message(
                        conversation_id=conversation_id,
                        task_id=task_id,
                        author_type="system",
                        kind="activity",
                        body="Work is ready for reviewer verification.",
                        metadata={"review": "awaiting_review"},
                        source="cyclone-review",
                    )
                    await publish_message(runtime, review_message, "task.awaiting_review")
                if inbox_item_id is not None:
                    await runtime.repository.mark_inbox_terminal(
                        inbox_item_id, "done" if mapped_status == "completed" else "failed"
                    )
                if mapped_status == "completed" and summary:
                    for instruction in parse_handoffs(summary):
                        await try_handoff(
                            runtime,
                            conversation_id=conversation_id,
                            from_task=task,
                            from_agent=agent,
                            instruction=instruction,
                            member_agents=member_agents,
                        )
                return
        task = await runtime.repository.set_task_terminal(
            task_id, status="blocked", result_summary="Hermes run status polling exceeded the Core observation window."
        )
        await runtime.repository.set_agent_status(agent.id, "blocked")
        if inbox_item_id is not None:
            await runtime.repository.mark_inbox_terminal(inbox_item_id, "failed", "observation window exceeded")
        message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task_id,
            author_type="system",
            kind="activity",
            body=f"Run {run_id} did not finish within the observation window (10 minutes). It may still be working in Hermes — check its status before retrying.",
            metadata={"hermes_run_id": run_id},
        )
        await publish_message(runtime, message, "agent.run.blocked")
        await runtime.event_bus.publish(
            conversation_id=conversation_id, event_type="task.updated", payload=task.model_dump(mode="json")
        )
    except Exception as error:
        task = await runtime.repository.set_task_terminal(
            task_id, status="blocked", result_summary="Cyclone could not observe the Hermes run safely."
        )
        await runtime.repository.set_agent_status(agent.id, "blocked")
        if inbox_item_id is not None:
            await runtime.repository.mark_inbox_terminal(inbox_item_id, "failed", type(error).__name__)
        message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task_id,
            author_type="system",
            kind="activity",
            body="Cyclone lost contact with Hermes while observing the run. No completion is claimed; inspect Hermes before retrying.",
            metadata={"hermes_run_id": run_id, "error_class": type(error).__name__},
        )
        await publish_message(runtime, message, "agent.run.observation_failed")
        await runtime.event_bus.publish(
            conversation_id=conversation_id, event_type="task.updated", payload=task.model_dump(mode="json")
        )
    finally:
        runtime.run_tasks.pop(run_id, None)


async def _handle_run_approval(
    runtime: Any,
    *,
    run_id: str,
    task_id: UUID,
    conversation_id: UUID,
    agent: AgentSummary,
) -> None:
    """Surface a real Hermes run approval as an interactive question in chat.

    Captured once per run: the gateway's approval.request event carries the
    redacted command and the real choices (once/session/always/deny). The
    question card in the UI resolves it through Core, which proxies the
    choice back to Hermes.
    """
    handled = getattr(runtime, "pending_run_approvals", None)
    if handled is None:
        handled = set()
        runtime.pending_run_approvals = handled
    if run_id in handled:
        return
    handled.add(run_id)

    request = await runtime.hermes.get_run_approval_request(run_id)
    command = str(request.get("command", "")) if request else ""
    choices = [str(choice) for choice in request.get("choices", [])] if request else ["once", "deny"]
    question = command or f"{agent.name} is asking for approval to continue."

    metadata: dict[str, object] = {
        "question": question,
        "choices": choices,
        "hermes_run_id": run_id,
        "question_card": True,
    }
    message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=task_id,
        author_type="agent",
        author_agent_id=agent.id,
        kind="approval",
        body=f"{agent.name} needs a decision: {question[:200]}",
        metadata=metadata,
        source="hermes",
    )
    await publish_message(runtime, message, "approval.requested")


async def start_agent_run(
    runtime: Any,
    *,
    conversation_id: UUID,
    agent: AgentSummary,
    task: TaskSummary,
    input_text: str,
    instructions: str,
    member_agents: list[AgentSummary],
    provider_override: str | None = None,
    model_override: str | None = None,
    inbox_item_id: UUID | None = None,
) -> AgentRunResponse:
    """Start a Hermes run for *task* and attach the observation monitor."""
    health_ok, health_detail = await runtime.hermes.health()
    if not health_ok:
        blocked = await runtime.repository.set_task_terminal(task.id, status="blocked", result_summary="Hermes is not ready.")
        await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task.id,
            author_type="system",
            kind="activity",
            body="Cyclone did not start the agent because Hermes is unavailable or not configured.",
            metadata={"reason": health_detail},
        )
        return AgentRunResponse(task=blocked, status="blocked", detail=health_detail)

    await runtime.repository.set_agent_status(agent.id, "working")
    try:
        started = await runtime.hermes.start_run(
            conversation_id=conversation_id,
            input_text=input_text,
            system_instructions=instructions,
            provider=provider_override or agent.provider,
            model=model_override or agent.model,
        )
    except RuntimeError as error:
        blocked = await runtime.repository.set_task_terminal(task.id, status="blocked", result_summary="Hermes rejected or could not accept the run.")
        await runtime.repository.set_agent_status(agent.id, "blocked")
        await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task.id,
            author_type="system",
            kind="activity",
            body="Cyclone did not fabricate a response: Hermes could not accept the run.",
            metadata={"error": str(error)},
        )
        return AgentRunResponse(task=blocked, status="blocked", detail=str(error))

    active_task = await runtime.repository.set_task_run(task.id, started.run_id)
    activity = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=task.id,
        author_type="agent",
        author_agent_id=agent.id,
        kind="activity",
        body=f"{agent.name} started work.",
        metadata={"hermes_run_id": started.run_id, "status": started.status},
        source="hermes",
    )
    await publish_message(runtime, activity, "agent.run.started")
    await runtime.event_bus.publish(conversation_id=conversation_id, event_type="task.updated", payload=active_task.model_dump(mode="json"))
    watcher = asyncio.create_task(
        _monitor_run(
            runtime,
            task_id=task.id,
            conversation_id=conversation_id,
            agent=agent,
            run_id=started.run_id,
            member_agents=member_agents,
            inbox_item_id=inbox_item_id,
        )
    )
    runtime.run_tasks[started.run_id] = watcher
    return AgentRunResponse(
        task=active_task,
        run=HermesRunStart(run_id=started.run_id, status=started.status),
        status="started",
        detail="Hermes accepted the task; Cyclone is observing its real run state.",
    )


async def dispatch_inbox_item(runtime: Any, item: Any, *, member_agents: list[AgentSummary] | None = None) -> AgentRunResponse | None:
    """Claim and execute one inbox item for an agent.

    Builds the focused context packet from the item's references, then starts
    a real Hermes run. Returns the run response, or None when the item could
    not be claimed (already processing / attempts exhausted).
    """
    claimed = await runtime.repository.claim_inbox_item(item.id)
    if claimed is None:
        return None
    agent = await runtime.repository.get_agent_by_id(claimed.agent_id)
    if claimed.conversation_id is None:
        await runtime.repository.mark_inbox_terminal(claimed.id, "failed", "no conversation reference")
        return None

    conversation = await runtime.repository.get_conversation(claimed.conversation_id, message_limit=100)
    if member_agents is None:
        member_agents = [member.agent for member in conversation.members if member.agent is not None]

    trigger_message = None
    if claimed.message_id is not None:
        trigger_message = next((m for m in conversation.messages if m.id == claimed.message_id), None)
    task = None
    if claimed.task_id is not None:
        try:
            task = await runtime.repository.get_task(claimed.task_id)
        except Exception:
            task = None

    instructions = await build_context_packet(
        runtime,
        agent=agent,
        conversation=conversation,
        member_agents=member_agents,
        trigger_message=trigger_message,
        task=task,
        extra=claimed.payload.get("extra"),
    )
    input_text = claimed.payload.get("input_text") or (trigger_message.body if trigger_message else task.title if task else "Continue.")
    if task is None:
        task = await runtime.repository.create_task(
            conversation_id=claimed.conversation_id,
            owner_agent_id=agent.id,
            title=(trigger_message.body if trigger_message else "Inbox request")[:300],
            objective=input_text,
        )
    return await start_agent_run(
        runtime,
        conversation_id=claimed.conversation_id,
        agent=agent,
        task=task,
        input_text=input_text,
        instructions=instructions,
        member_agents=member_agents,
        inbox_item_id=claimed.id,
    )


async def wake_agent(
    runtime: Any,
    *,
    agent: AgentSummary,
    conversation: Any,
    member_agents: list[AgentSummary],
    trigger_message: Message | None = None,
    task: TaskSummary | None = None,
    source_agent_id: UUID | None = None,
    event_type: str = "task.assigned",
    payload: dict[str, Any] | None = None,
    dispatch_now: bool = True,
) -> Any | None:
    """Enqueue a durable inbox item for *agent* and wake it (start a run).

    Returns the inbox item (or None when dispatch was deferred). The run is
    asynchronous: the sender never waits for the receiver's completion.
    """
    item = await runtime.repository.enqueue_inbox_item(
        agent_id=agent.id,
        event_type=event_type,
        conversation_id=conversation.id,
        message_id=trigger_message.id if trigger_message else None,
        task_id=task.id if task else None,
        source_agent_id=source_agent_id,
        payload=payload or {},
    )
    await runtime.event_bus.publish(
        conversation_id=conversation.id,
        event_type="agent.wake",
        payload={"agent_id": str(agent.id), "agent_slug": agent.slug, "event_type": event_type, "inbox_item_id": str(item.id)},
    )
    if dispatch_now:
        return await dispatch_inbox_item(runtime, item, member_agents=member_agents)
    return item


async def try_handoff(
    runtime: Any,
    *,
    conversation_id: UUID,
    from_task: TaskSummary,
    from_agent: AgentSummary,
    instruction: HandoffInstruction,
    member_agents: list[AgentSummary],
) -> None:
    """Execute a real agent-to-agent delegation from an explicit @HANDOFF."""
    target = next((member for member in member_agents if member.slug == instruction.to_slug), None)
    if target is None or target.id == from_agent.id:
        return  # Mention of a non-member or self: a reference, not a handoff.
    depth = await runtime.repository.handoff_depth(from_task.id)
    if depth >= MAX_HANDOFF_DEPTH:
        guard_message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=from_task.id,
            author_type="system",
            kind="activity",
            body=f"Delegation stopped: {from_agent.name} reached the handoff depth limit.",
            metadata={"from_slug": from_agent.slug, "to_slug": instruction.to_slug, "depth": depth},
        )
        await publish_message(runtime, guard_message, "agent.run.blocked")
        return
    child_task = await runtime.repository.create_task(
        conversation_id=conversation_id,
        owner_agent_id=target.id,
        title=instruction.summary[:300],
        objective=instruction.summary,
        parent_task_id=from_task.id,
        verification_criteria=instruction.acceptance_criteria,
    )
    await runtime.repository.create_handoff(
        task_id=from_task.id,
        from_agent_id=from_agent.id,
        to_agent_id=target.id,
        summary=instruction.summary,
        acceptance_criteria=instruction.acceptance_criteria,
    )
    handoff_message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=child_task.id,
        author_type="system",
        kind="handoff",
        body=f"{from_agent.name} handed work to {target.name}: {instruction.summary}",
        metadata={
            "from_slug": from_agent.slug,
            "to_slug": target.slug,
            "parent_task_id": str(from_task.id),
            "task_id": str(child_task.id),
            "acceptance_criteria": instruction.acceptance_criteria,
        },
    )
    await publish_message(runtime, handoff_message, "handoff.created")
    await wake_agent(
        runtime,
        agent=target,
        conversation=await runtime.repository.get_conversation(conversation_id, message_limit=1),
        member_agents=member_agents,
        task=child_task,
        source_agent_id=from_agent.id,
        event_type="handoff.received",
        payload={
            "input_text": instruction.summary,
            "extra": f"@{from_agent.slug} delegated this task to you with this request:\n{instruction.summary}",
        },
    )
