-- Cyclone visual-system data contract migration.
-- Safe to apply to an existing local PostgreSQL volume.

ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS avatar_shape TEXT;

ALTER TABLE agents
    DROP CONSTRAINT IF EXISTS agents_avatar_shape_check;

ALTER TABLE agents
    ADD CONSTRAINT agents_avatar_shape_check
    CHECK (avatar_shape IS NULL OR avatar_shape IN ('round', 'triangle', 'diamond', 'pebble', 'squircle'));

CREATE TABLE IF NOT EXISTS computer_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    status TEXT NOT NULL CHECK (status IN ('idle', 'working', 'waiting_for_user', 'done', 'error', 'unavailable')),
    instruction TEXT,
    stream_url TEXT,
    recent_frame_url TEXT,
    owner_type TEXT NOT NULL DEFAULT 'idle' CHECK (owner_type IN ('agent', 'human', 'idle')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS computer_sessions_agent_updated_idx
    ON computer_sessions (agent_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS computer_sessions_conversation_updated_idx
    ON computer_sessions (conversation_id, updated_at DESC);

DROP TRIGGER IF EXISTS computer_sessions_updated_at ON computer_sessions;
CREATE TRIGGER computer_sessions_updated_at
    BEFORE UPDATE ON computer_sessions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Existing seeded Chief should receive the first visual identity without
-- overwriting user-created agents or their deliberate customization.
UPDATE agents
SET avatar_color = '#70B7A7', avatar_shape = 'round'
WHERE slug = 'chief' AND avatar_shape IS NULL;

-- Shapes are optional; UI deterministically assigns a member of the same
-- character family when a persisted agent has no explicit shape yet.
-- Computer session records are only inserted by an authenticated runtime
-- integration. No placeholder session is created here.
