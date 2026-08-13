-- Durable workspace fabric: agent-owned resources, immutable content versions,
-- explicit access grants, recipient-scoped handoff snapshots, and edit leases.
-- This deliberately stores opaque canonical URIs rather than raw host paths.

CREATE TABLE IF NOT EXISTS resources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    kind TEXT NOT NULL CHECK (kind IN ('file', 'directory', 'repository', 'document', 'dataset', 'web_capture')),
    label TEXT NOT NULL CHECK (char_length(label) BETWEEN 1 AND 200),
    canonical_uri TEXT NOT NULL CHECK (char_length(canonical_uri) BETWEEN 5 AND 2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_agent_id, canonical_uri)
);

CREATE INDEX IF NOT EXISTS resources_owner_idx ON resources(owner_agent_id, created_at DESC);

CREATE TABLE IF NOT EXISTS resource_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id UUID NOT NULL REFERENCES resources(id) ON DELETE RESTRICT,
    version_number INT NOT NULL CHECK (version_number > 0),
    content_uri TEXT NOT NULL CHECK (char_length(content_uri) BETWEEN 5 AND 2048),
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    created_by_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (resource_id, version_number)
);

CREATE INDEX IF NOT EXISTS resource_versions_resource_idx
ON resource_versions(resource_id, version_number DESC);

CREATE TABLE IF NOT EXISTS resource_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id UUID NOT NULL REFERENCES resources(id) ON DELETE RESTRICT,
    grantee_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    granted_by_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    access TEXT NOT NULL CHECK (access IN ('private', 'view', 'edit', 'handoff')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by_agent_id UUID REFERENCES agents(id) ON DELETE RESTRICT,
    CHECK (grantee_agent_id <> granted_by_agent_id),
    CHECK (expires_at IS NULL OR expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK (
        (revoked_at IS NULL AND revoked_by_agent_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_agent_id IS NOT NULL)
    )
);


-- The repository must revoke or close an expired grant before replacing it.
-- This avoids an ambiguous union of permissions during a handoff transaction.
CREATE UNIQUE INDEX IF NOT EXISTS resource_grants_one_active_recipient_idx
ON resource_grants(resource_id, grantee_agent_id)
WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS resource_grants_active_lookup_idx
ON resource_grants(resource_id, grantee_agent_id, expires_at)
WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS resource_handoff_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handoff_id UUID NOT NULL REFERENCES handoffs(id) ON DELETE RESTRICT,
    resource_version_id UUID NOT NULL REFERENCES resource_versions(id) ON DELETE RESTRICT,
    recipient_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    created_by_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_by_agent_id UUID REFERENCES agents(id) ON DELETE RESTRICT,
    CHECK (recipient_agent_id <> created_by_agent_id),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK (
        (revoked_at IS NULL AND revoked_by_agent_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_agent_id IS NOT NULL)
    ),
    UNIQUE (handoff_id, resource_version_id, recipient_agent_id)
);

CREATE INDEX IF NOT EXISTS resource_handoff_snapshots_recipient_idx
ON resource_handoff_snapshots(recipient_agent_id, created_at DESC)
WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS resource_edit_leases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id UUID NOT NULL REFERENCES resources(id) ON DELETE RESTRICT,
    resource_version_id UUID NOT NULL REFERENCES resource_versions(id) ON DELETE RESTRICT,
    holder_agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    CHECK (expires_at > acquired_at),
    CHECK (released_at IS NULL OR released_at >= acquired_at)
);

-- Repositories close expired leases under a row lock before acquiring a new
-- lease.  The partial index is the final concurrency guard for live leases.
CREATE UNIQUE INDEX IF NOT EXISTS resource_edit_leases_one_active_resource_idx
ON resource_edit_leases(resource_id)
WHERE released_at IS NULL;

CREATE INDEX IF NOT EXISTS resource_edit_leases_holder_idx
ON resource_edit_leases(holder_agent_id, expires_at)
WHERE released_at IS NULL;

CREATE OR REPLACE FUNCTION cyclone_reject_resource_fabric_immutable_change()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable; append a new record instead', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS resource_versions_immutable ON resource_versions;
CREATE TRIGGER resource_versions_immutable
BEFORE UPDATE OR DELETE ON resource_versions
FOR EACH ROW EXECUTE FUNCTION cyclone_reject_resource_fabric_immutable_change();

CREATE OR REPLACE FUNCTION cyclone_guard_handoff_snapshot_change()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'resource_handoff_snapshots rows are immutable'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.revoked_at IS NULL
       AND NEW.revoked_at IS NOT NULL
       AND NEW.revoked_by_agent_id IS NOT NULL
       AND NEW.id = OLD.id
       AND NEW.handoff_id = OLD.handoff_id
       AND NEW.resource_version_id = OLD.resource_version_id
       AND NEW.recipient_agent_id = OLD.recipient_agent_id
       AND NEW.created_by_agent_id = OLD.created_by_agent_id
       AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'resource_handoff_snapshots permit revocation only'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS resource_handoff_snapshots_guard ON resource_handoff_snapshots;
CREATE TRIGGER resource_handoff_snapshots_guard
BEFORE UPDATE OR DELETE ON resource_handoff_snapshots
FOR EACH ROW EXECUTE FUNCTION cyclone_guard_handoff_snapshot_change();
