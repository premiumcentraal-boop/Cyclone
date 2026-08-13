"""Policy tests for durable, recipient-scoped workspace resources."""

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid5

import pytest

from app.resource_fabric import (
    ResourceAccess,
    ResourceAuthorizationError,
    ResourceFabric,
    ResourceFabricState,
    ResourceLeaseConflictError,
    ResourceValidationError,
)


NOW = datetime(2026, 8, 13, 12, 0, tzinfo=UTC)
OWNER = UUID("00000000-0000-0000-0000-000000000001")
EDITOR = UUID("00000000-0000-0000-0000-000000000002")
REVIEWER = UUID("00000000-0000-0000-0000-000000000003")
OUTSIDER = UUID("00000000-0000-0000-0000-000000000004")


def identity(name: str) -> UUID:
    return uuid5(UUID("8f0a1f2b-b3ca-4b21-b2ca-5f0b70efffff"), name)


def resource_with_initial_version() -> tuple[ResourceFabricState, UUID, UUID]:
    resource_id = identity("resource")
    version_id = identity("version-1")
    state, _ = ResourceFabric.create_resource(
        ResourceFabricState.empty(),
        resource_id=resource_id,
        owner_agent_id=OWNER,
        kind="repository",
        label="Cyclone Core",
        canonical_uri="workspace://chief/cyclone-core",
        created_at=NOW,
    )
    state, _ = ResourceFabric.seed_initial_version(
        state,
        version_id=version_id,
        resource_id=resource_id,
        actor_agent_id=OWNER,
        content_uri="git://chief/cyclone-core@abc123",
        content_sha256="a" * 64,
        created_at=NOW,
    )
    return state, resource_id, version_id


def test_private_grant_is_metadata_only_and_view_requires_a_real_view_grant() -> None:
    state, resource_id, _ = resource_with_initial_version()
    state, private_grant = ResourceFabric.grant_access(
        state,
        grant_id=identity("private-grant"),
        resource_id=resource_id,
        actor_agent_id=OWNER,
        grantee_agent_id=REVIEWER,
        access=ResourceAccess.PRIVATE,
        created_at=NOW,
    )

    assert ResourceFabric.assert_can_discover(
        state, resource_id=resource_id, actor_agent_id=REVIEWER, at=NOW
    ).label == "Cyclone Core"
    with pytest.raises(ResourceAuthorizationError, match="private grant"):
        ResourceFabric.assert_can_view(state, resource_id=resource_id, actor_agent_id=REVIEWER, at=NOW)

    state, _ = ResourceFabric.revoke_grant(
        state, grant_id=private_grant.id, actor_agent_id=OWNER, revoked_at=NOW + timedelta(seconds=1)
    )
    state, _ = ResourceFabric.grant_access(
        state,
        grant_id=identity("view-grant"),
        resource_id=resource_id,
        actor_agent_id=OWNER,
        grantee_agent_id=REVIEWER,
        access="view",
        created_at=NOW + timedelta(seconds=2),
    )
    assert ResourceFabric.assert_can_view(
        state, resource_id=resource_id, actor_agent_id=REVIEWER, at=NOW + timedelta(seconds=2)
    ).id == resource_id


def test_edit_requires_an_explicit_grant_and_a_single_current_version_lease() -> None:
    state, resource_id, version_one_id = resource_with_initial_version()
    state, _ = ResourceFabric.grant_access(
        state,
        grant_id=identity("editor-grant"),
        resource_id=resource_id,
        actor_agent_id=OWNER,
        grantee_agent_id=EDITOR,
        access=ResourceAccess.EDIT,
        created_at=NOW,
    )
    state, developer_lease = ResourceFabric.acquire_edit_lease(
        state,
        lease_id=identity("editor-lease"),
        resource_id=resource_id,
        actor_agent_id=EDITOR,
        expected_version_id=version_one_id,
        acquired_at=NOW,
        expires_at=NOW + timedelta(minutes=10),
    )

    with pytest.raises(ResourceLeaseConflictError):
        ResourceFabric.acquire_edit_lease(
            state,
            lease_id=identity("owner-competing-lease"),
            resource_id=resource_id,
            actor_agent_id=OWNER,
            expected_version_id=version_one_id,
            acquired_at=NOW + timedelta(seconds=1),
            expires_at=NOW + timedelta(minutes=10),
        )

    with pytest.raises(ResourceAuthorizationError, match="lease holder"):
        ResourceFabric.release_edit_lease(
            state,
            lease_id=developer_lease.id,
            actor_agent_id=OWNER,
            released_at=NOW + timedelta(seconds=1),
        )

    state, version_two = ResourceFabric.append_version(
        state,
        version_id=identity("version-2"),
        resource_id=resource_id,
        actor_agent_id=EDITOR,
        expected_version_id=version_one_id,
        content_uri="git://chief/cyclone-core@def456",
        content_sha256="b" * 64,
        created_at=NOW + timedelta(seconds=2),
    )
    assert version_two.version_number == 2
    assert state.versions[version_one_id].content_sha256 == "a" * 64
    assert state.leases[developer_lease.id].released_at == NOW + timedelta(seconds=2)

    with pytest.raises(ResourceValidationError, match="resource changed"):
        ResourceFabric.acquire_edit_lease(
            state,
            lease_id=identity("stale-lease"),
            resource_id=resource_id,
            actor_agent_id=EDITOR,
            expected_version_id=version_one_id,
            acquired_at=NOW + timedelta(seconds=3),
            expires_at=NOW + timedelta(minutes=10),
        )


def test_revoking_an_edit_grant_immediately_closes_its_lease() -> None:
    state, resource_id, version_one_id = resource_with_initial_version()
    state, grant = ResourceFabric.grant_access(
        state,
        grant_id=identity("revoke-editor-grant"),
        resource_id=resource_id,
        actor_agent_id=OWNER,
        grantee_agent_id=EDITOR,
        access="edit",
        created_at=NOW,
    )
    state, lease = ResourceFabric.acquire_edit_lease(
        state,
        lease_id=identity("revoked-editor-lease"),
        resource_id=resource_id,
        actor_agent_id=EDITOR,
        expected_version_id=version_one_id,
        acquired_at=NOW,
        expires_at=NOW + timedelta(minutes=10),
    )
    state, _ = ResourceFabric.revoke_grant(
        state,
        grant_id=grant.id,
        actor_agent_id=OWNER,
        revoked_at=NOW + timedelta(seconds=1),
    )

    assert state.leases[lease.id].released_at == NOW + timedelta(seconds=1)
    with pytest.raises(ResourceAuthorizationError, match="No active grant"):
        ResourceFabric.append_version(
            state,
            version_id=identity("forbidden-version"),
            resource_id=resource_id,
            actor_agent_id=EDITOR,
            expected_version_id=version_one_id,
            content_uri="git://chief/cyclone-core@cannot-write",
            content_sha256="c" * 64,
            created_at=NOW + timedelta(seconds=2),
        )


def test_expired_lease_is_closed_before_a_new_editor_lease_is_acquired() -> None:
    state, resource_id, version_one_id = resource_with_initial_version()
    state, first_lease = ResourceFabric.acquire_edit_lease(
        state,
        lease_id=identity("expired-owner-lease"),
        resource_id=resource_id,
        actor_agent_id=OWNER,
        expected_version_id=version_one_id,
        acquired_at=NOW,
        expires_at=NOW + timedelta(seconds=5),
    )
    state, second_lease = ResourceFabric.acquire_edit_lease(
        state,
        lease_id=identity("replacement-owner-lease"),
        resource_id=resource_id,
        actor_agent_id=OWNER,
        expected_version_id=version_one_id,
        acquired_at=NOW + timedelta(seconds=6),
        expires_at=NOW + timedelta(minutes=1),
    )

    assert state.leases[first_lease.id].released_at == NOW + timedelta(seconds=6)
    assert state.leases[second_lease.id].released_at is None


def test_handoff_snapshot_is_recipient_scoped_and_independently_revocable() -> None:
    state, resource_id, version_one_id = resource_with_initial_version()
    state, _ = ResourceFabric.grant_access(
        state,
        grant_id=identity("handoff-grant"),
        resource_id=resource_id,
        actor_agent_id=OWNER,
        grantee_agent_id=EDITOR,
        access=ResourceAccess.HANDOFF,
        created_at=NOW,
    )
    state, snapshot = ResourceFabric.create_handoff_snapshot(
        state,
        snapshot_id=identity("review-snapshot"),
        handoff_id=identity("handoff"),
        resource_version_id=version_one_id,
        actor_agent_id=EDITOR,
        recipient_agent_id=REVIEWER,
        created_at=NOW + timedelta(seconds=1),
    )

    assert ResourceFabric.assert_can_read_handoff_snapshot(
        state, snapshot_id=snapshot.id, actor_agent_id=REVIEWER, at=NOW + timedelta(seconds=1)
    ).resource_version_id == version_one_id
    with pytest.raises(ResourceAuthorizationError):
        ResourceFabric.assert_can_read_handoff_snapshot(
            state, snapshot_id=snapshot.id, actor_agent_id=EDITOR, at=NOW + timedelta(seconds=1)
        )
    with pytest.raises(ResourceAuthorizationError):
        ResourceFabric.assert_can_view(
            state, resource_id=resource_id, actor_agent_id=REVIEWER, at=NOW + timedelta(seconds=1)
        )

    state, _ = ResourceFabric.revoke_handoff_snapshot(
        state,
        snapshot_id=snapshot.id,
        actor_agent_id=OWNER,
        revoked_at=NOW + timedelta(seconds=2),
    )
    with pytest.raises(ResourceAuthorizationError, match="revoked"):
        ResourceFabric.assert_can_read_handoff_snapshot(
            state, snapshot_id=snapshot.id, actor_agent_id=REVIEWER, at=NOW + timedelta(seconds=2)
        )


def test_malformed_storage_references_and_naive_times_fail_closed() -> None:
    with pytest.raises(ResourceValidationError, match="timezone-aware"):
        ResourceFabric.create_resource(
            ResourceFabricState.empty(),
            resource_id=identity("bad-time"),
            owner_agent_id=OWNER,
            kind="file",
            label="Build notes",
            canonical_uri="workspace://chief/notes.md",
            created_at=datetime(2026, 8, 13, 12, 0),
        )

    with pytest.raises(ResourceValidationError, match="canonical URI"):
        ResourceFabric.create_resource(
            ResourceFabricState.empty(),
            resource_id=identity("bad-path"),
            owner_agent_id=OWNER,
            kind="file",
            label="Build notes",
            canonical_uri="C:/Users/Agent/secrets.txt",
            created_at=NOW,
        )
