-- A retained local operator must survive normal cleanup and sidebar deletion.
-- Protection is enforced in Postgres as well as the application layer so an
-- accidental API or maintenance query cannot remove the Super User's identity
-- or personal control-room conversation.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'member'
        CHECK (role IN ('member', 'super_user')),
    ADD COLUMN IF NOT EXISTS is_protected BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS is_protected BOOLEAN NOT NULL DEFAULT false;

CREATE OR REPLACE FUNCTION prevent_protected_cyclone_delete() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.is_protected THEN
        RAISE EXCEPTION 'Protected Cyclone records cannot be deleted.' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS users_prevent_protected_delete ON users;
CREATE TRIGGER users_prevent_protected_delete
    BEFORE DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION prevent_protected_cyclone_delete();

DROP TRIGGER IF EXISTS conversations_prevent_protected_delete ON conversations;
CREATE TRIGGER conversations_prevent_protected_delete
    BEFORE DELETE ON conversations
    FOR EACH ROW EXECUTE FUNCTION prevent_protected_cyclone_delete();

-- The local operator selected this identity explicitly. The migration remains
-- portable: new installations simply have no protected user until setup.
UPDATE users
SET role = 'super_user', is_protected = true
WHERE telegram_chat_id = 7690834361
  AND display_name = 'Super User'
  AND initials = 'KA';

UPDATE conversations c
SET is_protected = true
FROM users u
WHERE u.is_protected
  AND c.hermes_conversation_key = 'telegram-' || u.telegram_chat_id::TEXT;
