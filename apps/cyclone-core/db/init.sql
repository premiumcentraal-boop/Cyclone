-- Cyclone Core owns user-facing product state. Hermes and n8n retain their
-- own data volumes/databases; Core stores only normalized links and events.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE cyclone_agent_status AS ENUM ('offline', 'idle', 'working', 'waiting_for_approval', 'blocked', 'error');
CREATE TYPE cyclone_conversation_kind AS ENUM ('direct', 'cluster', 'telegram', 'routine');
CREATE TYPE cyclone_message_kind AS ENUM ('message', 'activity', 'task', 'handoff', 'approval', 'result', 'automation', 'system');
CREATE TYPE cyclone_task_status AS ENUM ('queued', 'running', 'awaiting_approval', 'awaiting_review', 'completed', 'blocked', 'failed', 'cancelled');
CREATE TYPE cyclone_approval_status AS ENUM ('pending', 'approved', 'denied', 'expired', 'cancelled');

CREATE TABLE IF NOT EXISTS agents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug TEXT NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    role TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    avatar_color TEXT NOT NULL DEFAULT '#5865F2' CHECK (avatar_color ~ '^#[0-9A-Fa-f]{6}$'),
    avatar_shape TEXT CHECK (avatar_shape IS NULL OR avatar_shape IN ('round', 'triangle', 'diamond', 'pebble', 'squircle')),
    system_instructions TEXT NOT NULL DEFAULT '',
    provider TEXT,
    model TEXT,
    hermes_profile TEXT NOT NULL DEFAULT 'default',
    workspace_path TEXT NOT NULL DEFAULT '/workspace',
    tool_permissions JSONB NOT NULL DEFAULT '{}'::jsonb,
    status cyclone_agent_status NOT NULL DEFAULT 'idle',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    kind cyclone_conversation_kind NOT NULL DEFAULT 'direct',
    hermes_conversation_key TEXT UNIQUE,
    project_key TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS conversation_members (
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    agent_id UUID REFERENCES agents(id) ON DELETE CASCADE,
    member_type TEXT NOT NULL CHECK (member_type IN ('agent', 'human', 'system')),
    display_name TEXT NOT NULL,
    member_role TEXT NOT NULL DEFAULT 'member',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, member_type, display_name),
    CHECK ((member_type = 'agent' AND agent_id IS NOT NULL) OR (member_type <> 'agent' AND agent_id IS NULL))
);

CREATE TABLE IF NOT EXISTS tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    parent_task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    owner_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    requested_by TEXT NOT NULL DEFAULT 'administrator',
    title TEXT NOT NULL CHECK (char_length(title) BETWEEN 1 AND 300),
    objective TEXT NOT NULL,
    status cyclone_task_status NOT NULL DEFAULT 'queued',
    priority SMALLINT NOT NULL DEFAULT 0 CHECK (priority BETWEEN -10 AND 10),
    dependencies JSONB NOT NULL DEFAULT '[]'::jsonb,
    result_summary TEXT,
    verification_criteria TEXT,
    hermes_run_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    author_type TEXT NOT NULL CHECK (author_type IN ('human', 'agent', 'system', 'automation')),
    author_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    kind cyclone_message_kind NOT NULL DEFAULT 'message',
    body TEXT NOT NULL DEFAULT '',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    source TEXT NOT NULL DEFAULT 'cyclone',
    external_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS messages_conversation_created_idx ON messages(conversation_id, created_at);
CREATE INDEX IF NOT EXISTS tasks_conversation_status_idx ON tasks(conversation_id, status, created_at);

CREATE TABLE IF NOT EXISTS handoffs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    from_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    to_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    summary TEXT NOT NULL,
    artifact_paths JSONB NOT NULL DEFAULT '[]'::jsonb,
    acceptance_criteria TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    requested_by_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    capability TEXT NOT NULL,
    target TEXT NOT NULL,
    scope JSONB NOT NULL DEFAULT '{}'::jsonb,
    expected_effect TEXT NOT NULL,
    policy_reason TEXT NOT NULL,
    status cyclone_approval_status NOT NULL DEFAULT 'pending',
    decided_by TEXT,
    decided_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

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

CREATE INDEX IF NOT EXISTS computer_sessions_agent_updated_idx ON computer_sessions (agent_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS computer_sessions_conversation_updated_idx ON computer_sessions (conversation_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS routines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug TEXT NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    owner_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    instructions TEXT NOT NULL DEFAULT '',
    n8n_workflow_id TEXT,
    trigger_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS automation_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    routine_id UUID REFERENCES routines(id) ON DELETE SET NULL,
    external_event_id TEXT UNIQUE,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'received' CHECK (status IN ('received', 'processed', 'failed')),
    error TEXT
);

CREATE TABLE IF NOT EXISTS knowledge_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vault_path TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    category TEXT NOT NULL,
    project_key TEXT,
    agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    keywords TSVECTOR NOT NULL DEFAULT ''::tsvector,
    content_fingerprint TEXT NOT NULL,
    source_conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS knowledge_entries_keywords_idx ON knowledge_entries USING GIN (keywords);

CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id UUID,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    outcome TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS agents_updated_at ON agents;
CREATE TRIGGER agents_updated_at BEFORE UPDATE ON agents FOR EACH ROW EXECUTE FUNCTION set_updated_at();
DROP TRIGGER IF EXISTS conversations_updated_at ON conversations;
CREATE TRIGGER conversations_updated_at BEFORE UPDATE ON conversations FOR EACH ROW EXECUTE FUNCTION set_updated_at();
DROP TRIGGER IF EXISTS tasks_updated_at ON tasks;
CREATE TRIGGER tasks_updated_at BEFORE UPDATE ON tasks FOR EACH ROW EXECUTE FUNCTION set_updated_at();
DROP TRIGGER IF EXISTS routines_updated_at ON routines;
CREATE TRIGGER routines_updated_at BEFORE UPDATE ON routines FOR EACH ROW EXECUTE FUNCTION set_updated_at();
DROP TRIGGER IF EXISTS computer_sessions_updated_at ON computer_sessions;
CREATE TRIGGER computer_sessions_updated_at BEFORE UPDATE ON computer_sessions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO agents (slug, name, role, description, avatar_color, avatar_shape, system_instructions, hermes_profile)
VALUES (
  'chief',
  'Chief',
  'Coordinator',
  'Coordinates work, delegates to specialists when useful, and reports verified results.',
  '#70B7A7',
  'round',
  'You are Chief, the Cyclone coordinator. Work transparently, delegate only when useful, track verification, do not fabricate completed work, and request approval for consequential actions.',
  'default'
)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO conversations (title, kind, hermes_conversation_key)
VALUES ('Cyclone Welcome', 'direct', 'cyclone-welcome-chief')
ON CONFLICT (hermes_conversation_key) DO NOTHING;

INSERT INTO conversation_members (conversation_id, agent_id, member_type, display_name, member_role)
SELECT c.id, a.id, 'agent', a.name, 'chief'
FROM conversations c
JOIN agents a ON a.slug = 'chief'
WHERE c.hermes_conversation_key = 'cyclone-welcome-chief'
ON CONFLICT DO NOTHING;
