--liquibase formatted sql

--changeset yac:005-dev-seed-data context:dev
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM users WHERE username IN ('alice', 'bob', 'carol')
-- Dev-only seed (R1-55). Runs ONLY when the `dev` context is active (application-dev.yml).
-- The precondition skips the seed (marking it ran) if any seed username already exists, so a
-- volume that already holds a real 'alice' does not crash startup on the username unique key.
-- For a full clean seed, reset the volume: `docker compose down -v`.
-- Fixed UUIDs + precomputed BCrypt hashes. Documented dev password for every seeded user: devpass123

-- Users
INSERT INTO users (id, email, username, display_name, password_hash) VALUES
    ('11111111-1111-1111-1111-111111111111', 'alice@dev.local', 'alice', 'Alice', '$2a$10$pauvHIZLixXQwj.TqZDKROKx8nLNr1WssHmBNptSkFmZwUhyqxPsO'),
    ('22222222-2222-2222-2222-222222222222', 'bob@dev.local', 'bob', 'Bob', '$2a$10$pauvHIZLixXQwj.TqZDKROKx8nLNr1WssHmBNptSkFmZwUhyqxPsO'),
    ('44444444-4444-4444-4444-444444444444', 'carol@dev.local', 'carol', 'Carol', '$2a$10$pauvHIZLixXQwj.TqZDKROKx8nLNr1WssHmBNptSkFmZwUhyqxPsO')
ON CONFLICT (id) DO NOTHING;

-- A public room owned by Alice
INSERT INTO rooms (id, name, description, visibility, owner_id, next_watermark) VALUES
    ('33333333-3333-3333-3333-333333333333', 'General', 'Dev general chat', 'PUBLIC',
     '11111111-1111-1111-1111-111111111111', 1)
ON CONFLICT (id) DO NOTHING;

-- Memberships: Alice owner, Bob member
INSERT INTO room_members (room_id, user_id, role) VALUES
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'OWNER'),
    ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222222', 'MEMBER')
ON CONFLICT (room_id, user_id) DO NOTHING;

-- Unread markers at the room's current watermark
INSERT INTO unread_markers (user_id, room_id, last_read_watermark) VALUES
    ('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', 0),
    ('22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', 0)
ON CONFLICT (user_id, room_id) DO NOTHING;

-- Alice and Bob are friends
INSERT INTO friendships (id, requester_id, recipient_id, status) VALUES
    ('55555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111',
     '22222222-2222-2222-2222-222222222222', 'ACCEPTED')
ON CONFLICT (requester_id, recipient_id) DO NOTHING;
