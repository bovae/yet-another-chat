--liquibase formatted sql

--changeset yac:004-schema-hardening
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM (SELECT name FROM rooms GROUP BY name HAVING count(*) > 1) dups
-- R1-29: the precondition halts the migration if any duplicate room names exist, so the
-- unconditional unique index below can be added cleanly.

DROP INDEX IF EXISTS idx_rooms_name_public;
CREATE UNIQUE INDEX idx_rooms_name_unique ON rooms (name);

-- R1-07 backstop: watermarks unique within a room.
CREATE UNIQUE INDEX uq_messages_room_watermark ON messages (room_id, watermark);

-- R1-47: FK indexes on hot delete/lookup paths.
CREATE INDEX idx_attachments_message ON attachments (message_id);
CREATE INDEX idx_messages_reply_to ON messages (reply_to_id);
CREATE INDEX idx_room_invitations_invitee ON room_invitations (invitee_id);

-- R1-28: account deletion preserves others' history and bans issued.
-- messages.sender_id -> nullable + ON DELETE SET NULL.
ALTER TABLE messages ALTER COLUMN sender_id DROP NOT NULL;
ALTER TABLE messages DROP CONSTRAINT messages_sender_id_fkey;
ALTER TABLE messages ADD CONSTRAINT messages_sender_id_fkey
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE SET NULL;

-- room_bans.banned_by_id -> nullable + ON DELETE SET NULL (ban stays in force).
ALTER TABLE room_bans ALTER COLUMN banned_by_id DROP NOT NULL;
ALTER TABLE room_bans DROP CONSTRAINT room_bans_banned_by_id_fkey;
ALTER TABLE room_bans ADD CONSTRAINT room_bans_banned_by_id_fkey
    FOREIGN KEY (banned_by_id) REFERENCES users(id) ON DELETE SET NULL;
