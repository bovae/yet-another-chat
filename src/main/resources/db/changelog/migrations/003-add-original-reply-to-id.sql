--liquibase formatted sql

--changeset yac:003-add-original-reply-to-id

-- Add original_reply_to_id column to preserve the reply reference even after the
-- original message is deleted (reply_to_id is set to NULL by ON DELETE SET NULL).
ALTER TABLE messages ADD COLUMN original_reply_to_id UUID;

-- Backfill from existing reply_to_id values
UPDATE messages SET original_reply_to_id = reply_to_id WHERE reply_to_id IS NOT NULL;
