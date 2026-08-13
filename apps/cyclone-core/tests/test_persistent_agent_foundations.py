"""Acceptance contract for Cyclone's persistent-agent vertical slice.

These checks intentionally compose the public, pure foundations without a
database, model run, or browser service double.  The live runbook in
``docs/PERSISTENT_AGENT_ACCEPTANCE.md`` covers the corresponding Core/Hermes
workflow once the HTTP and MCP adapters are connected.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid5

import pytest

from app.agent_environments import PrivateEnvironmentManager
from app.camofox_client import BrowserAccessGrant, CamofoxPolicyError
from app.resource_fabric import (
    ResourceAccess,
    ResourceAuthorizationError,
    ResourceFabric,
    ResourceFabricState,
)


NOW = datetime(2026, 8, 13, 12, 0, tzinfo=UTC)
IDENTITY_NAMESPACE = UUID("bf9ef07d-18fa-4f64-9e9e-4469caa9f0fd")
RESEARCHER = uuid5(IDENTITY_NAMESPACE, "researcher")
DEVELOPER = uuid5(IDENTITY_NAMESPACE, "developer")
REVIEWER = uuid5(IDENTITY_NAMESPACE, "reviewer")
CONVERSATION = uuid5(IDENTITY_NAMESPACE, "persistent-agent-acceptance")
RESOURCE = uuid5(IDENTITY_NAMESPACE, "research-brief")
VERSION = uuid5(IDENTITY_NAMESPACE, "research-brief-v1")
HANDOFF = uuid5(IDENTITY_NAMESPACE, "research-to-developer")
SNAPSHOT = uuid5(IDENTITY_NAMESPACE, "developer-to-reviewer-snapshot")


def test_persistent_agent_vertical_slice_has_private_state_and_a_narrow_review_handoff(tmp_path) -> None:
    """A restart must retain private work while exposing only granted review input.

    This is intentionally an end-to-end policy composition, not an HTTP test:
    the environment manager and resource fabric are public pure service APIs.
    It proves the contract Core's future repository/MCP adapters must preserve.
    """

    environments_root = tmp_path / "agent-environments"
    manager = PrivateEnvironmentManager(environments_root)
    research_environment = manager.provision(
        agent_id=RESEARCHER, agent_slug="researcher", template_key="research"
    ).record
    developer_environment = manager.provision(
        agent_id=DEVELOPER, agent_slug="developer", template_key="developer"
    ).record
    reviewer_environment = manager.provision(
        agent_id=REVIEWER, agent_slug="reviewer", template_key="reviewer"
    ).record

    # Private spaces have no shared directory and never alias another agent's
    # browser profile or workspace.
    environments = (research_environment, developer_environment, reviewer_environment)
    assert len({environment.paths.workspace for environment in environments}) == 3
    assert len({environment.paths.browser_profile for environment in environments}) == 3
    assert all(not (environment.paths.agent_root / "shared").exists() for environment in environments)
    private_brief = research_environment.paths.workspace / "brief.md"
    private_brief.write_text("Verified sources and implementation constraints.", encoding="utf-8")
    assert not (developer_environment.paths.workspace / private_brief.name).exists()
    assert not (reviewer_environment.paths.workspace / private_brief.name).exists()

    # A browser grant is server-owned, agent-private, conversation-bounded,
    # origin-bounded, and cannot be expanded by a tool invocation.
    browser_grant = BrowserAccessGrant(
        agent_id=RESEARCHER,
        conversation_id=CONVERSATION,
        resource_id=RESOURCE,
        allowed_origins=("https://docs.example.test",),
    )
    assert browser_grant.profile_id == f"cyclone-agent-{RESEARCHER.hex}"
    assert browser_grant.session_key == f"cyclone-conversation-{CONVERSATION.hex}"
    browser_grant.assert_url_allowed("https://docs.example.test/build-bible")
    with pytest.raises(CamofoxPolicyError, match="not included"):
        browser_grant.assert_url_allowed("https://ungranted.example.test/")

    # The research output becomes an explicitly granted resource.  The owner
    # grants only handoff capability to the developer: it is not a blanket
    # writable workspace mount and is not visible to the reviewer.
    state, resource = ResourceFabric.create_resource(
        ResourceFabricState.empty(),
        resource_id=RESOURCE,
        owner_agent_id=RESEARCHER,
        kind="document",
        label="Research brief",
        canonical_uri="workspace://researcher/brief.md",
        created_at=NOW,
    )
    state, version = ResourceFabric.seed_initial_version(
        state,
        version_id=VERSION,
        resource_id=resource.id,
        actor_agent_id=RESEARCHER,
        content_uri="workspace://researcher/brief.md#sha256",
        content_sha256="a" * 64,
        created_at=NOW,
    )
    state, _ = ResourceFabric.grant_access(
        state,
        grant_id=uuid5(IDENTITY_NAMESPACE, "research-to-developer-handoff-grant"),
        resource_id=resource.id,
        actor_agent_id=RESEARCHER,
        grantee_agent_id=DEVELOPER,
        access=ResourceAccess.HANDOFF,
        created_at=NOW,
    )
    state, snapshot = ResourceFabric.create_handoff_snapshot(
        state,
        snapshot_id=SNAPSHOT,
        handoff_id=HANDOFF,
        resource_version_id=version.id,
        actor_agent_id=DEVELOPER,
        recipient_agent_id=REVIEWER,
        created_at=NOW + timedelta(seconds=1),
    )

    # A reviewer can accept/reject based on the immutable snapshot, but cannot
    # read the sender's live resource.  This is the required input boundary for
    # a reviewer task transition in the live Core workflow.
    accepted_input = ResourceFabric.assert_can_read_handoff_snapshot(
        state,
        snapshot_id=snapshot.id,
        actor_agent_id=REVIEWER,
        at=NOW + timedelta(seconds=1),
    )
    assert accepted_input.resource_version_id == version.id
    with pytest.raises(ResourceAuthorizationError, match="No active grant"):
        ResourceFabric.assert_can_view(
            state,
            resource_id=resource.id,
            actor_agent_id=REVIEWER,
            at=NOW + timedelta(seconds=1),
        )

    # A Core restart reconciles the durable private layout without losing work
    # or widening access.  The repaired browser profile remains agent-private.
    research_environment.paths.browser_profile.rmdir()
    restarted_manager = PrivateEnvironmentManager(environments_root)
    reconciliation = restarted_manager.reconcile(agent_id=RESEARCHER, agent_slug="researcher")
    assert reconciliation.created is False
    assert reconciliation.repaired_paths == ("browser_profile",)
    assert reconciliation.record.paths.browser_profile.is_dir()
    assert private_brief.read_text(encoding="utf-8") == "Verified sources and implementation constraints."
    assert ResourceFabric.assert_can_read_handoff_snapshot(
        state,
        snapshot_id=snapshot.id,
        actor_agent_id=REVIEWER,
        at=NOW + timedelta(seconds=2),
    ).id == snapshot.id
