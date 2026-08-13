-- Persistent private-environment inventory.  Paths are relative to the
-- operator-configured root so a controlled root relocation is restart-safe.
-- No shared workspace is implied by an environment record.

CREATE TABLE IF NOT EXISTS agent_environments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL UNIQUE REFERENCES agents(id) ON DELETE CASCADE,
    template_key TEXT NOT NULL CHECK (template_key IN ('research', 'developer', 'reviewer')),
    relative_root_path TEXT NOT NULL UNIQUE
        CHECK (relative_root_path ~ '^agents/[0-9a-f]{32}$'),
    layout_version SMALLINT NOT NULL DEFAULT 1 CHECK (layout_version >= 1),
    lifecycle_state TEXT NOT NULL DEFAULT 'provisioning'
        CHECK (lifecycle_state IN ('provisioning', 'ready', 'stopped', 'reconciling', 'error', 'retired')),
    health_state TEXT NOT NULL DEFAULT 'unknown'
        CHECK (health_state IN ('unknown', 'healthy', 'degraded', 'unhealthy')),
    last_reconciled_at TIMESTAMPTZ,
    last_healthy_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS agent_environments_lifecycle_health_idx
ON agent_environments (lifecycle_state, health_state, updated_at DESC);

DROP TRIGGER IF EXISTS agent_environments_updated_at ON agent_environments;
CREATE TRIGGER agent_environments_updated_at
BEFORE UPDATE ON agent_environments
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
