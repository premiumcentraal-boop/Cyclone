from datetime import datetime, timezone
from uuid import uuid4

from app.contracts import AgentSummary, ComputerOwnershipRequest, ComputerSessionResponse


def test_agent_contract_exposes_optional_visual_shape_without_requiring_a_visual_default() -> None:
    agent = AgentSummary(
        id=uuid4(),
        slug="chief",
        name="Chief",
        role="Coordinator",
        description="Coordinates work.",
        avatar_color="#70B7A7",
        avatar_shape="round",
        status="idle",
        hermes_profile="default",
        workspace_path="/workspace",
    )

    assert agent.avatar_shape == "round"


def test_computer_session_contract_represents_only_a_real_session_descriptor() -> None:
    session = ComputerSessionResponse(
        id="session-1",
        agent_id="chief",
        status="waiting_for_user",
        instruction="Complete the sign-in step.",
        stream_url="https://computer.example/stream",
        recent_frame_url="https://computer.example/frame.png",
        owner="human",
        updated_at=datetime.now(timezone.utc),
    )

    assert session.stream_url.endswith("/stream")
    assert session.owner == "human"


def test_computer_ownership_contract_only_allows_one_authoritative_controller() -> None:
    assert ComputerOwnershipRequest(owner="human").owner == "human"
    assert ComputerOwnershipRequest(owner="agent").owner == "agent"
    assert ComputerOwnershipRequest(owner="idle").owner == "idle"
