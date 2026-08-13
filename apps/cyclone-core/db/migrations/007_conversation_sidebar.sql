-- Durable sidebar controls used by the Grok-style conversation menu.
-- Hiding a conversation is intentionally reversible through the API; deleting
-- a conversation remains the only destructive operation.

ALTER TABLE conversations ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS is_unread BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS sidebar_section TEXT;
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMPTZ;

ALTER TABLE conversations DROP CONSTRAINT IF EXISTS conversations_sidebar_section_check;
ALTER TABLE conversations ADD CONSTRAINT conversations_sidebar_section_check
CHECK (sidebar_section IS NULL OR char_length(sidebar_section) BETWEEN 1 AND 80);

CREATE INDEX IF NOT EXISTS conversations_sidebar_idx
ON conversations (is_pinned DESC, updated_at DESC)
WHERE hidden_at IS NULL;
