"""Pure authorization rules for Cyclone's durable workspace fabric.

This module deliberately contains no database or filesystem calls.  The Core
repository will persist the records in ``009_resource_fabric.sql`` and use the
functions below inside its transactions.  Keeping the policy here means every
caller (HTTP, MCP, automation, and a future environment manager) has one
auditable definition of resource sharing.

Resource versions and handoff snapshots are append-only.  A recipient of a
handoff snapshot receives that exact version, not access to the sender's live
workspace.  Revoking a live grant does not rewrite a completed handoff; a
snapshot has its own explicit revocation path.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime
from enum import Enum
from types import MappingProxyType
from typing import Mapping, TypeVar
from uuid import UUID


class ResourceFabricError(ValueError):
    """Base class for a rejected workspace-fabric operation."""


class ResourceValidationError(ResourceFabricError):
    """A caller supplied malformed or inconsistent resource data."""


class ResourceNotFoundError(ResourceFabricError):
    """The requested resource-fabric record does not exist."""


class ResourceAuthorizationError(ResourceFabricError):
    """The caller has no active authorization for the requested operation."""


class ResourceLeaseConflictError(ResourceFabricError):
    """A resource is currently leased to a different editor."""


class ResourceAccess(str, Enum):
    """A deliberately small, non-escalating access vocabulary.

    ``private`` lets a grantee discover a resource's metadata only; it never
    grants its contents.  The resource owner always has full access implicitly.
    ``handoff`` grants read plus the ability to create a recipient-scoped,
    immutable snapshot.  It does not grant edit rights.
    """

    PRIVATE = "private"
    VIEW = "view"
    EDIT = "edit"
    HANDOFF = "handoff"


class ResourceKind(str, Enum):
    FILE = "file"
    DIRECTORY = "directory"
    REPOSITORY = "repository"
    DOCUMENT = "document"
    DATASET = "dataset"
    WEB_CAPTURE = "web_capture"


@dataclass(frozen=True, slots=True)
class Resource:
    id: UUID
    owner_agent_id: UUID
    kind: ResourceKind
    label: str
    canonical_uri: str
    created_at: datetime


@dataclass(frozen=True, slots=True)
class ResourceVersion:
    id: UUID
    resource_id: UUID
    version_number: int
    content_uri: str
    content_sha256: str
    created_by_agent_id: UUID
    created_at: datetime


@dataclass(frozen=True, slots=True)
class ResourceGrant:
    id: UUID
    resource_id: UUID
    grantee_agent_id: UUID
    granted_by_agent_id: UUID
    access: ResourceAccess
    created_at: datetime
    expires_at: datetime | None = None
    revoked_at: datetime | None = None
    revoked_by_agent_id: UUID | None = None


@dataclass(frozen=True, slots=True)
class HandoffSnapshot:
    id: UUID
    handoff_id: UUID
    resource_version_id: UUID
    recipient_agent_id: UUID
    created_by_agent_id: UUID
    created_at: datetime
    revoked_at: datetime | None = None
    revoked_by_agent_id: UUID | None = None


@dataclass(frozen=True, slots=True)
class EditLease:
    id: UUID
    resource_id: UUID
    resource_version_id: UUID
    holder_agent_id: UUID
    acquired_at: datetime
    expires_at: datetime
    released_at: datetime | None = None


T = TypeVar("T")


def _frozen_copy(values: Mapping[UUID, T] | None = None) -> Mapping[UUID, T]:
    return MappingProxyType(dict(values or {}))


@dataclass(frozen=True, slots=True)
class ResourceFabricState:
    """An immutable snapshot of the resource-fabric records.

    Repository adapters reconstruct this from one transaction and persist the
    returned delta before committing.  Tests can use the same state directly
    without pretending that a filesystem or browser exists.
    """

    resources: Mapping[UUID, Resource] = field(default_factory=_frozen_copy)
    versions: Mapping[UUID, ResourceVersion] = field(default_factory=_frozen_copy)
    grants: Mapping[UUID, ResourceGrant] = field(default_factory=_frozen_copy)
    snapshots: Mapping[UUID, HandoffSnapshot] = field(default_factory=_frozen_copy)
    leases: Mapping[UUID, EditLease] = field(default_factory=_frozen_copy)

    @classmethod
    def empty(cls) -> "ResourceFabricState":
        return cls()


def _updated_state(
    state: ResourceFabricState,
    *,
    resources: Mapping[UUID, Resource] | None = None,
    versions: Mapping[UUID, ResourceVersion] | None = None,
    grants: Mapping[UUID, ResourceGrant] | None = None,
    snapshots: Mapping[UUID, HandoffSnapshot] | None = None,
    leases: Mapping[UUID, EditLease] | None = None,
) -> ResourceFabricState:
    return ResourceFabricState(
        resources=_frozen_copy(resources if resources is not None else state.resources),
        versions=_frozen_copy(versions if versions is not None else state.versions),
        grants=_frozen_copy(grants if grants is not None else state.grants),
        snapshots=_frozen_copy(snapshots if snapshots is not None else state.snapshots),
        leases=_frozen_copy(leases if leases is not None else state.leases),
    )


def _require_time(value: datetime, field_name: str) -> None:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ResourceValidationError(f"{field_name} must be timezone-aware")


def _require_text(value: str, field_name: str, *, maximum: int) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > maximum:
        raise ResourceValidationError(f"{field_name} must contain 1 to {maximum} characters")
    if any(ord(character) < 32 for character in normalized):
        raise ResourceValidationError(f"{field_name} cannot contain control characters")
    return normalized


def _require_uri(value: str, field_name: str) -> str:
    normalized = _require_text(value, field_name, maximum=2048)
    if "://" not in normalized or normalized.startswith("/"):
        raise ResourceValidationError(
            f"{field_name} must be an opaque canonical URI, not a local path"
        )
    return normalized


def _require_sha256(value: str) -> str:
    normalized = value.strip().lower()
    if len(normalized) != 64 or any(char not in "0123456789abcdef" for char in normalized):
        raise ResourceValidationError("content_sha256 must be a lowercase SHA-256 digest")
    return normalized


def _resource(state: ResourceFabricState, resource_id: UUID) -> Resource:
    resource = state.resources.get(resource_id)
    if resource is None:
        raise ResourceNotFoundError("Resource was not found")
    return resource


def _version(state: ResourceFabricState, version_id: UUID) -> ResourceVersion:
    version = state.versions.get(version_id)
    if version is None:
        raise ResourceNotFoundError("Resource version was not found")
    return version


def _active_grant(
    state: ResourceFabricState, *, resource_id: UUID, agent_id: UUID, at: datetime
) -> ResourceGrant | None:
    matches = [
        grant
        for grant in state.grants.values()
        if grant.resource_id == resource_id
        and grant.grantee_agent_id == agent_id
        and grant.revoked_at is None
        and (grant.expires_at is None or grant.expires_at > at)
    ]
    if len(matches) > 1:
        # The partial unique index in the migration prevents this in storage.
        # Keeping the guard makes an incorrectly assembled adapter state fail
        # closed instead of choosing a more-permissive grant.
        raise ResourceValidationError("More than one active grant exists for this recipient")
    return matches[0] if matches else None


def _is_owner(resource: Resource, agent_id: UUID) -> bool:
    return resource.owner_agent_id == agent_id


def _require_owner(resource: Resource, actor_agent_id: UUID) -> None:
    if not _is_owner(resource, actor_agent_id):
        raise ResourceAuthorizationError("Only the resource owner can manage grants or snapshots")


def _require_access(
    state: ResourceFabricState,
    *,
    resource: Resource,
    actor_agent_id: UUID,
    at: datetime,
    required: ResourceAccess,
) -> None:
    if _is_owner(resource, actor_agent_id):
        return
    grant = _active_grant(state, resource_id=resource.id, agent_id=actor_agent_id, at=at)
    if grant is None:
        raise ResourceAuthorizationError("No active grant for this resource")
    allowed: dict[ResourceAccess, frozenset[ResourceAccess]] = {
        ResourceAccess.PRIVATE: frozenset(),
        ResourceAccess.VIEW: frozenset({ResourceAccess.VIEW}),
        ResourceAccess.EDIT: frozenset({ResourceAccess.VIEW, ResourceAccess.EDIT}),
        ResourceAccess.HANDOFF: frozenset({ResourceAccess.VIEW, ResourceAccess.HANDOFF}),
    }
    if required not in allowed[grant.access]:
        raise ResourceAuthorizationError(
            f"A {grant.access.value} grant does not permit {required.value} access"
        )


def _latest_version(state: ResourceFabricState, resource_id: UUID) -> ResourceVersion:
    versions = [version for version in state.versions.values() if version.resource_id == resource_id]
    if not versions:
        raise ResourceNotFoundError("Resource has no version yet")
    return max(versions, key=lambda version: version.version_number)


def _active_lease(
    state: ResourceFabricState, *, resource_id: UUID, at: datetime
) -> EditLease | None:
    leases = [
        lease
        for lease in state.leases.values()
        if lease.resource_id == resource_id and lease.released_at is None and lease.expires_at > at
    ]
    if len(leases) > 1:
        raise ResourceValidationError("More than one active edit lease exists for this resource")
    return leases[0] if leases else None


def _close_expired_leases(state: ResourceFabricState, *, at: datetime) -> ResourceFabricState:
    changed = {
        lease_id: replace(lease, released_at=at)
        if lease.released_at is None and lease.expires_at <= at
        else lease
        for lease_id, lease in state.leases.items()
    }
    return _updated_state(state, leases=changed)


class ResourceFabric:
    """Pure state transitions for versioned, explicitly shared resources."""

    @staticmethod
    def create_resource(
        state: ResourceFabricState,
        *,
        resource_id: UUID,
        owner_agent_id: UUID,
        kind: ResourceKind | str,
        label: str,
        canonical_uri: str,
        created_at: datetime,
    ) -> tuple[ResourceFabricState, Resource]:
        _require_time(created_at, "created_at")
        if resource_id in state.resources:
            raise ResourceValidationError("Resource id already exists")
        try:
            parsed_kind = ResourceKind(kind)
        except ValueError as error:
            raise ResourceValidationError("Unsupported resource kind") from error
        resource = Resource(
            id=resource_id,
            owner_agent_id=owner_agent_id,
            kind=parsed_kind,
            label=_require_text(label, "label", maximum=200),
            canonical_uri=_require_uri(canonical_uri, "canonical_uri"),
            created_at=created_at,
        )
        resources = dict(state.resources)
        resources[resource.id] = resource
        return _updated_state(state, resources=resources), resource

    @staticmethod
    def grant_access(
        state: ResourceFabricState,
        *,
        grant_id: UUID,
        resource_id: UUID,
        actor_agent_id: UUID,
        grantee_agent_id: UUID,
        access: ResourceAccess | str,
        created_at: datetime,
        expires_at: datetime | None = None,
    ) -> tuple[ResourceFabricState, ResourceGrant]:
        _require_time(created_at, "created_at")
        if expires_at is not None:
            _require_time(expires_at, "expires_at")
            if expires_at <= created_at:
                raise ResourceValidationError("expires_at must be later than created_at")
        if grant_id in state.grants:
            raise ResourceValidationError("Grant id already exists")
        resource = _resource(state, resource_id)
        _require_owner(resource, actor_agent_id)
        if grantee_agent_id == resource.owner_agent_id:
            raise ResourceValidationError("The owner has implicit access and cannot be granted access")
        try:
            parsed_access = ResourceAccess(access)
        except ValueError as error:
            raise ResourceValidationError("Unsupported resource access level") from error
        if _active_grant(state, resource_id=resource_id, agent_id=grantee_agent_id, at=created_at):
            raise ResourceValidationError("Revoke the existing active grant before replacing it")
        grant = ResourceGrant(
            id=grant_id,
            resource_id=resource_id,
            grantee_agent_id=grantee_agent_id,
            granted_by_agent_id=actor_agent_id,
            access=parsed_access,
            created_at=created_at,
            expires_at=expires_at,
        )
        grants = dict(state.grants)
        grants[grant.id] = grant
        return _updated_state(state, grants=grants), grant

    @staticmethod
    def revoke_grant(
        state: ResourceFabricState,
        *,
        grant_id: UUID,
        actor_agent_id: UUID,
        revoked_at: datetime,
    ) -> tuple[ResourceFabricState, ResourceGrant]:
        _require_time(revoked_at, "revoked_at")
        grant = state.grants.get(grant_id)
        if grant is None:
            raise ResourceNotFoundError("Resource grant was not found")
        if grant.revoked_at is not None:
            raise ResourceValidationError("Resource grant has already been revoked")
        resource = _resource(state, grant.resource_id)
        _require_owner(resource, actor_agent_id)
        if revoked_at < grant.created_at:
            raise ResourceValidationError("revoked_at cannot be before created_at")
        revoked = replace(grant, revoked_at=revoked_at, revoked_by_agent_id=actor_agent_id)
        grants = dict(state.grants)
        grants[grant_id] = revoked
        # Revocation is immediate: an editor cannot continue writing under a
        # lease obtained before the owner withdrew edit access.
        leases = {
            lease_id: replace(lease, released_at=revoked_at)
            if lease.resource_id == resource.id
            and lease.holder_agent_id == grant.grantee_agent_id
            and lease.released_at is None
            else lease
            for lease_id, lease in state.leases.items()
        }
        return _updated_state(state, grants=grants, leases=leases), revoked

    @staticmethod
    def append_version(
        state: ResourceFabricState,
        *,
        version_id: UUID,
        resource_id: UUID,
        actor_agent_id: UUID,
        expected_version_id: UUID,
        content_uri: str,
        content_sha256: str,
        created_at: datetime,
    ) -> tuple[ResourceFabricState, ResourceVersion]:
        _require_time(created_at, "created_at")
        if version_id in state.versions:
            raise ResourceValidationError("Resource version id already exists")
        resource = _resource(state, resource_id)
        current = _latest_version(state, resource_id)
        if current.id != expected_version_id:
            raise ResourceValidationError("The resource changed; acquire a lease for the latest version")
        _require_access(
            state,
            resource=resource,
            actor_agent_id=actor_agent_id,
            at=created_at,
            required=ResourceAccess.EDIT,
        )
        lease = _active_lease(state, resource_id=resource_id, at=created_at)
        if lease is None or lease.holder_agent_id != actor_agent_id or lease.resource_version_id != current.id:
            raise ResourceAuthorizationError("Editing requires an active lease for the current version")
        version = ResourceVersion(
            id=version_id,
            resource_id=resource_id,
            version_number=current.version_number + 1,
            content_uri=_require_uri(content_uri, "content_uri"),
            content_sha256=_require_sha256(content_sha256),
            created_by_agent_id=actor_agent_id,
            created_at=created_at,
        )
        versions = dict(state.versions)
        versions[version.id] = version
        # A version transition consumes the lease; an editor must explicitly
        # reacquire before making another edit.  This prevents stale writes.
        leases = dict(state.leases)
        leases[lease.id] = replace(lease, released_at=created_at)
        return _updated_state(state, versions=versions, leases=leases), version

    @staticmethod
    def seed_initial_version(
        state: ResourceFabricState,
        *,
        version_id: UUID,
        resource_id: UUID,
        actor_agent_id: UUID,
        content_uri: str,
        content_sha256: str,
        created_at: datetime,
    ) -> tuple[ResourceFabricState, ResourceVersion]:
        """Create version 1 while registering a new owner-controlled resource."""
        _require_time(created_at, "created_at")
        if version_id in state.versions:
            raise ResourceValidationError("Resource version id already exists")
        resource = _resource(state, resource_id)
        _require_owner(resource, actor_agent_id)
        if any(version.resource_id == resource_id for version in state.versions.values()):
            raise ResourceValidationError("Initial version is only valid for a new resource")
        version = ResourceVersion(
            id=version_id,
            resource_id=resource_id,
            version_number=1,
            content_uri=_require_uri(content_uri, "content_uri"),
            content_sha256=_require_sha256(content_sha256),
            created_by_agent_id=actor_agent_id,
            created_at=created_at,
        )
        versions = dict(state.versions)
        versions[version.id] = version
        return _updated_state(state, versions=versions), version

    @staticmethod
    def acquire_edit_lease(
        state: ResourceFabricState,
        *,
        lease_id: UUID,
        resource_id: UUID,
        actor_agent_id: UUID,
        expected_version_id: UUID,
        acquired_at: datetime,
        expires_at: datetime,
    ) -> tuple[ResourceFabricState, EditLease]:
        _require_time(acquired_at, "acquired_at")
        _require_time(expires_at, "expires_at")
        if expires_at <= acquired_at:
            raise ResourceValidationError("Lease expiry must be later than acquisition")
        if lease_id in state.leases:
            raise ResourceValidationError("Lease id already exists")
        state = _close_expired_leases(state, at=acquired_at)
        resource = _resource(state, resource_id)
        current = _latest_version(state, resource_id)
        if current.id != expected_version_id:
            raise ResourceValidationError("The resource changed; refresh before acquiring a lease")
        _require_access(
            state,
            resource=resource,
            actor_agent_id=actor_agent_id,
            at=acquired_at,
            required=ResourceAccess.EDIT,
        )
        existing = _active_lease(state, resource_id=resource_id, at=acquired_at)
        if existing is not None:
            raise ResourceLeaseConflictError(
                f"Resource is leased by another agent until {existing.expires_at.isoformat()}"
            )
        lease = EditLease(
            id=lease_id,
            resource_id=resource_id,
            resource_version_id=current.id,
            holder_agent_id=actor_agent_id,
            acquired_at=acquired_at,
            expires_at=expires_at,
        )
        leases = dict(state.leases)
        leases[lease.id] = lease
        return _updated_state(state, leases=leases), lease

    @staticmethod
    def release_edit_lease(
        state: ResourceFabricState,
        *,
        lease_id: UUID,
        actor_agent_id: UUID,
        released_at: datetime,
    ) -> tuple[ResourceFabricState, EditLease]:
        _require_time(released_at, "released_at")
        lease = state.leases.get(lease_id)
        if lease is None:
            raise ResourceNotFoundError("Edit lease was not found")
        if lease.holder_agent_id != actor_agent_id:
            raise ResourceAuthorizationError("Only the lease holder can release it")
        if lease.released_at is not None:
            raise ResourceValidationError("Edit lease has already been released")
        if released_at < lease.acquired_at:
            raise ResourceValidationError("released_at cannot be before acquired_at")
        released = replace(lease, released_at=released_at)
        leases = dict(state.leases)
        leases[lease_id] = released
        return _updated_state(state, leases=leases), released

    @staticmethod
    def create_handoff_snapshot(
        state: ResourceFabricState,
        *,
        snapshot_id: UUID,
        handoff_id: UUID,
        resource_version_id: UUID,
        actor_agent_id: UUID,
        recipient_agent_id: UUID,
        created_at: datetime,
    ) -> tuple[ResourceFabricState, HandoffSnapshot]:
        _require_time(created_at, "created_at")
        if snapshot_id in state.snapshots:
            raise ResourceValidationError("Handoff snapshot id already exists")
        version = _version(state, resource_version_id)
        resource = _resource(state, version.resource_id)
        if recipient_agent_id == actor_agent_id:
            raise ResourceValidationError("A handoff snapshot must be addressed to a different agent")
        _require_access(
            state,
            resource=resource,
            actor_agent_id=actor_agent_id,
            at=created_at,
            required=ResourceAccess.HANDOFF,
        )
        duplicate = next(
            (
                snapshot
                for snapshot in state.snapshots.values()
                if snapshot.handoff_id == handoff_id
                and snapshot.resource_version_id == resource_version_id
                and snapshot.recipient_agent_id == recipient_agent_id
            ),
            None,
        )
        if duplicate is not None:
            raise ResourceValidationError("This resource version is already attached to that handoff recipient")
        snapshot = HandoffSnapshot(
            id=snapshot_id,
            handoff_id=handoff_id,
            resource_version_id=version.id,
            recipient_agent_id=recipient_agent_id,
            created_by_agent_id=actor_agent_id,
            created_at=created_at,
        )
        snapshots = dict(state.snapshots)
        snapshots[snapshot.id] = snapshot
        return _updated_state(state, snapshots=snapshots), snapshot

    @staticmethod
    def revoke_handoff_snapshot(
        state: ResourceFabricState,
        *,
        snapshot_id: UUID,
        actor_agent_id: UUID,
        revoked_at: datetime,
    ) -> tuple[ResourceFabricState, HandoffSnapshot]:
        _require_time(revoked_at, "revoked_at")
        snapshot = state.snapshots.get(snapshot_id)
        if snapshot is None:
            raise ResourceNotFoundError("Handoff snapshot was not found")
        if snapshot.revoked_at is not None:
            raise ResourceValidationError("Handoff snapshot has already been revoked")
        version = _version(state, snapshot.resource_version_id)
        resource = _resource(state, version.resource_id)
        _require_owner(resource, actor_agent_id)
        if revoked_at < snapshot.created_at:
            raise ResourceValidationError("revoked_at cannot be before created_at")
        revoked = replace(snapshot, revoked_at=revoked_at, revoked_by_agent_id=actor_agent_id)
        snapshots = dict(state.snapshots)
        snapshots[snapshot.id] = revoked
        return _updated_state(state, snapshots=snapshots), revoked

    @staticmethod
    def assert_can_discover(
        state: ResourceFabricState, *, resource_id: UUID, actor_agent_id: UUID, at: datetime
    ) -> Resource:
        _require_time(at, "at")
        resource = _resource(state, resource_id)
        if _is_owner(resource, actor_agent_id):
            return resource
        if _active_grant(state, resource_id=resource_id, agent_id=actor_agent_id, at=at) is None:
            raise ResourceAuthorizationError("No active grant for this resource")
        return resource

    @staticmethod
    def assert_can_view(
        state: ResourceFabricState, *, resource_id: UUID, actor_agent_id: UUID, at: datetime
    ) -> Resource:
        _require_time(at, "at")
        resource = _resource(state, resource_id)
        _require_access(
            state,
            resource=resource,
            actor_agent_id=actor_agent_id,
            at=at,
            required=ResourceAccess.VIEW,
        )
        return resource

    @staticmethod
    def assert_can_edit(
        state: ResourceFabricState, *, resource_id: UUID, actor_agent_id: UUID, at: datetime
    ) -> Resource:
        _require_time(at, "at")
        resource = _resource(state, resource_id)
        _require_access(
            state,
            resource=resource,
            actor_agent_id=actor_agent_id,
            at=at,
            required=ResourceAccess.EDIT,
        )
        return resource

    @staticmethod
    def assert_can_read_handoff_snapshot(
        state: ResourceFabricState, *, snapshot_id: UUID, actor_agent_id: UUID, at: datetime
    ) -> HandoffSnapshot:
        _require_time(at, "at")
        snapshot = state.snapshots.get(snapshot_id)
        if snapshot is None:
            raise ResourceNotFoundError("Handoff snapshot was not found")
        if snapshot.revoked_at is not None and snapshot.revoked_at <= at:
            raise ResourceAuthorizationError("Handoff snapshot access has been revoked")
        version = _version(state, snapshot.resource_version_id)
        resource = _resource(state, version.resource_id)
        if actor_agent_id not in {resource.owner_agent_id, snapshot.recipient_agent_id}:
            raise ResourceAuthorizationError("Handoff snapshots are only readable by their recipient")
        return snapshot

