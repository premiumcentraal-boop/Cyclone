"""Focused context packets for agent runs.

Do NOT dump the entire group history into every Hermes turn. This module
assembles a compact, role-scoped packet: identity, operating rules, crew
roster, the triggering message (plus reply context), current task state, a
short recent window, and relevant vault knowledge.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from .contracts import AgentSummary, Message, TaskSummary

OPERATING_RULES = """Operating rules for Cyclone team work:
- You are a persistent team member, not a session. Act when directly mentioned,
  assigned work, or when your role clearly owns the next step. Otherwise stay quiet.
- Maintain one clear owner per task. Use the Cyclone tools (send_agent_message,
  handoff_task) to contact teammates; do not claim another agent's work and never
  fabricate another agent's response.
- When you hand work to a teammate, say so in the group (e.g. "@Writer this is
  yours") AND use the handoff_task tool so the transfer is real.
- If you receive a review, fix the findings; do not argue unless the finding is
  factually wrong.
- Do not send acknowledgement-only chatter ("Got it", "Thanks", "Sounds good").
  A message should assign, ask, clarify, report, hand off, review, block, approve,
  or complete.
- Do not mark work done until the real work is complete."""


def build_agent_context(
    *,
    agent: AgentSummary,
    member_agents: list[AgentSummary],
    trigger_message: Message | None = None,
    reply_to_message: Message | None = None,
    task: TaskSummary | None = None,
    recent_messages: list[Message] | None = None,
    knowledge_entries: list[dict[str, Any]] | None = None,
    extra: str | None = None,
) -> str:
    """Build the system instructions for one agent turn."""
    parts: list[str] = []

    identity = [f"You are {agent.name}, a persistent member of the Cyclone agent team."]
    if agent.role:
        identity.append(f"Your role: {agent.role}")
    if agent.description:
        identity.append(agent.description)
    parts.append("\n".join(identity))

    teammates = [member for member in member_agents if member.id != agent.id]
    if teammates:
        roster = ["Your teammates in this conversation (mention by @slug):"]
        for member in teammates:
            role = f" — {member.role}" if member.role else ""
            roster.append(f"  @{member.slug}{role}")
        parts.append("\n".join(roster))

    parts.append(OPERATING_RULES)

    if trigger_message is not None:
        block = ["The message you are responding to:"]
        if trigger_message.author_type == "human":
            block.append(f"  Human ({trigger_message.author_name}): {trigger_message.body}")
        else:
            block.append(f"  {trigger_message.author_name}: {trigger_message.body}")
        if reply_to_message is not None:
            block.append(
                f"It is a reply to: {reply_to_message.author_name}: {reply_to_message.body[:500]}"
            )
        parts.append("\n".join(block))

    if task is not None:
        task_block = [
            "Current task state:",
            f"  Task: {task.title}",
            f"  Owner: {'you' if task.owner_agent_id == agent.id else 'another agent'} (status: {task.status})",
        ]
        if task.objective and task.objective != task.title:
            task_block.append(f"  Objective: {task.objective[:800]}")
        if task.verification_criteria:
            task_block.append(f"  Verification: {task.verification_criteria[:400]}")
        parts.append("\n".join(task_block))

    if recent_messages:
        lines = ["Recent conversation:"]
        for message in recent_messages[-6:]:
            if message.kind in ("activity", "task") and not message.body:
                continue
            name = "You" if message.author_type == "human" else message.author_name
            body = message.body.replace("\n", " ")[:300]
            lines.append(f"  {name}: {body}")
        parts.append("\n".join(lines))

    if knowledge_entries:
        lines = ["Relevant knowledge from the Cyclone vault:"]
        for entry in knowledge_entries:
            lines.append(f"- {entry['title']} ({entry['vault_path']})")
        parts.append("\n".join(lines))

    if extra:
        parts.append(extra)

    return "\n\n".join(part for part in parts if part)
