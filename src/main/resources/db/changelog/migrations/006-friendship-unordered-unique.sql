--liquibase formatted sql

--changeset yac:006-friendship-unordered-unique
-- R5-11: the base UNIQUE(requester_id, recipient_id) covers only the ordered pair, so a pair could
-- hold two rows (A->B and B->A) — e.g. from mutual concurrent friend requests — yielding a
-- duplicated contact and a unfriend that only removes one row. Drop any existing unordered
-- duplicate (keep the earliest row), then enforce at most one friendship per unordered pair.

DELETE FROM friendships f
    USING friendships g
    WHERE f.ctid > g.ctid
      AND LEAST(f.requester_id, f.recipient_id) = LEAST(g.requester_id, g.recipient_id)
      AND GREATEST(f.requester_id, f.recipient_id) = GREATEST(g.requester_id, g.recipient_id);

CREATE UNIQUE INDEX idx_friendships_pair_unique
    ON friendships (LEAST(requester_id, recipient_id), GREATEST(requester_id, recipient_id));
