-- Cyclone collaboration layer: semantic mentions, agent inboxes, reactions,
-- task dependencies, artifacts, reply threading, and the 'group' conversation kind.
-- Applies on top of init.sql / 002_visual_system.sql. Numbered migrations run
-- in lexical order by scripts/apply_migrations.py.

ALTER TYPE cyclone_conversation_kind ADD VALUE IF NOT EXISTS 'group';
ALTER TYPE cyclone_task_status ADD VALUE IF NOT EXISTS 'changes_requested';

-- Reply threading: a message can reference the message it answers.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reply_to_message_id UUID REFERENCES messages(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS messages_reply_to_idx ON messages(reply_to_message_id);

-- Semantic mentions: structured database objects, never string sniffing.
CREATE TABLE IF NOT EXISTS message_mentions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    mention_type TEXT NOT NULL CHECK (mention_type IN ('agent', 'group', 'everyone', 'routine', 'connector')),
    target_agent_id UUID REFERENCES agents(id) ON DELETE CASCADE,
    target_slug TEXT,
    position_start INT,
    position_end INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS message_mentions_message_idx ON message_mentions(message_id);
CREATE INDEX IF NOT EXISTS message_mentions_agent_idx ON message_mentions(target_agent_id);

-- Durable asynchronous inbox: every wake event for a persistent agent.
CREATE TABLE IF NOT EXISTS agent_inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    conversation_id UUID REFERENCES conversations(id) ON DELETE CASCADE,
    message_id UUID REFERENCES messages(id) ON DELETE CASCADE,
    task_id UUID REFERENCES tasks(id) ON DELETE CASCADE,
    source_agent_id UUID REFERENCES agents(id) ON DELETE CASCADE,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'processing', 'done', 'failed')),
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS agent_inbox_pending_idx ON agent_inbox(agent_id, status, created_at);
CREATE INDEX IF NOT EXISTS agent_inbox_status_idx ON agent_inbox(status, created_at);

-- Reactions: lightweight acknowledgement only (never approval).
CREATE TABLE IF NOT EXISTS reactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    actor_type TEXT NOT NULL CHECK (actor_type IN ('human', 'agent')),
    actor_agent_id UUID REFERENCES agents(id) ON DELETE CASCADE,
    emoji TEXT NOT NULL CHECK (char_length(emoji) BETWEEN 1 AND 8),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, actor_type, actor_agent_id, emoji)
);
CREATE INDEX IF NOT EXISTS reactions_message_idx ON reactions(message_id);

-- Explicit task dependencies (parallelizable vs sequential work).
CREATE TABLE IF NOT EXISTS task_dependencies (
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    depends_on_task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, depends_on_task_id),
    CHECK (task_id <> depends_on_task_id)
);

-- Artifact references shared across handoffs.
CREATE TABLE IF NOT EXISTS artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    created_by_agent_id UUID REFERENCES agents(id) ON DELETE SET NULL,
    type TEXT NOT NULL DEFAULT 'file',
    path TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS artifacts_task_idx ON artifacts(task_id);
CREATE INDEX IF NOT EXISTS artifacts_conversation_idx ON artifacts(conversation_id);
