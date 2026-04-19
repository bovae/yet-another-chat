--liquibase formatted sql

--changeset yac:002-schema-changes

-- Add watermark column to messages
ALTER TABLE messages ADD COLUMN watermark BIGINT NOT NULL DEFAULT 0;

-- Add next_watermark counter to rooms
ALTER TABLE rooms ADD COLUMN next_watermark BIGINT NOT NULL DEFAULT 1;

-- Alter unread_markers: drop old columns, add last_read_watermark
ALTER TABLE unread_markers DROP COLUMN last_read_message_id;
ALTER TABLE unread_markers DROP COLUMN unread_count;
ALTER TABLE unread_markers ADD COLUMN last_read_watermark BIGINT;

-- Create password_reset_tokens table
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes from design

-- Message history cursor pagination (critical for 100K+ rooms)
CREATE INDEX idx_messages_room_watermark ON messages (room_id, watermark);

-- Catalog search
CREATE INDEX idx_rooms_visibility_name ON rooms (visibility, name);

-- Unread computation
CREATE INDEX idx_unread_markers_user ON unread_markers (user_id);

-- Friendship lookups
CREATE INDEX idx_friendships_requester ON friendships (requester_id, status);
-- Replace single-column index from 001 with composite index
DROP INDEX IF EXISTS idx_friendships_recipient;
CREATE INDEX idx_friendships_recipient ON friendships (recipient_id, status);

-- Room ban checks (join-time validation)
CREATE INDEX idx_room_bans_room_user ON room_bans (room_id, user_id);

-- User ban checks (message-time validation)
CREATE INDEX idx_user_bans_blocker ON user_bans (blocker_id, blocked_id);
CREATE INDEX idx_user_bans_blocked ON user_bans (blocked_id, blocker_id);

-- Room members by room (for broadcast recipient lookup)
CREATE INDEX idx_room_members_room ON room_members (room_id);
