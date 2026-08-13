"""Group coordinator: deterministic routing of group messages.

Cyclone groups are crews of persistent agents. A message routes to ONE primary
responder unless it explicitly addresses someone else or broadcasts:

1. A leading ``@slug`` addresses that member directly.
2. Explicit mentions of members route to the first mentioned member.
3. ``@everyone`` is a broadcast: every member receives an inbox event, but no
   agent is auto-woken (no acknowledgement spam).
4. Otherwise the router prefers deterministic signals: the owner of the most
   recent active task, then the author of the last agent message, then the
   group's coordinator (chief) when present, then the first member.

LLM-based semantic routing is deliberately NOT used here; the signals above
cover the common cases with zero tokens and full testability.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .contracts import AgentSummary, ConversationDetail, TaskSummary


@dataclass(frozen=True)
class RoutingDecision:
    target: AgentSummary | None
    broadcast: bool = False
    reason: str = ""


def route_group_message(
    *,
    conversation: ConversationDetail,
    requested_agent: AgentSummary,
    member_agents: list[AgentSummary],
    addressed_slug: str | None,
    mentioned_slugs: list[str],
    has_everyone: bool,
    active_task: TaskSummary | None,
    last_agent_message_author_id: str | None,
) -> RoutingDecision:
    """Decide who should respond to a message in a group conversation."""
    members_by_slug = {member.slug: member for member in member_agents}
    member_ids = {str(member.id) for member in member_agents}

    # 1. Direct addressing wins over everything.
    if addressed_slug is not None and addressed_slug in members_by_slug:
        return RoutingDecision(members_by_slug[addressed_slug], reason=f"addressed as @{addressed_slug}")

    # 2. @everyone is a broadcast, not a single-owner message.
    if has_everyone:
        return RoutingDecision(None, broadcast=True, reason="@everyone broadcast")

    # 3. Explicit mentions route to the first mentioned member.
    for slug in mentioned_slugs:
        if slug in members_by_slug:
            return RoutingDecision(members_by_slug[slug], reason=f"mentioned @{slug}")

    # 4. Active task ownership.
    if active_task is not None and active_task.owner_agent_id is not None:
        owner = next((member for member in member_agents if member.id == active_task.owner_agent_id), None)
        if owner is not None:
            return RoutingDecision(owner, reason=f"owns active task {active_task.id}")

    # 5. Continuation: the last agent to speak in the group.
    if last_agent_message_author_id is not None:
        author = next((member for member in member_agents if str(member.id) == last_agent_message_author_id), None)
        if author is not None:
            return RoutingDecision(author, reason="last agent speaker")

    # 6. Coordinator first, else the first member.
    coordinator = members_by_slug.get("chief")
    if coordinator is not None:
        return RoutingDecision(coordinator, reason="group coordinator")
    if member_agents:
        return RoutingDecision(member_agents[0], reason="first group member")
    return RoutingDecision(requested_agent, reason="fallback to requested agent")
