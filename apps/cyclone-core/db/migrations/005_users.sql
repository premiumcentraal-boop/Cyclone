-- Cyclone users: persistent identities of the humans who talk to the agents.
-- The Telegram integration maps a Telegram chat to a Cyclone user record.

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT NOT NULL,
    initials TEXT NOT NULL DEFAULT '',
    telegram_chat_id BIGINT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS users_telegram_chat_idx ON users(telegram_chat_id);
