from uuid import uuid4

from app.context import build_agent_context
from app.contracts import AgentSummary


def test_persisted_agent_description_is_included_in_hermes_context() -> None:
    agent = AgentSummary(
        id=uuid4(),
        slug="research",
        name="Research",
        role="Evidence specialist",
        description="Use primary sources and clearly mark uncertainty.",
        avatar_color="#9159FE",
        avatar_shape="triangle",
        status="idle",
        hermes_profile="default",
        workspace_path="/workspace",
    )

    context = build_agent_context(agent=agent, member_agents=[agent])

    assert "Your role: Evidence specialist" in context
    assert "Use primary sources and clearly mark uncertainty." in context
