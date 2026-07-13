# query-performance — Delta Spec

Covers R1-44…47.

## ADDED Requirements

### Requirement: Unread fan-out without per-member queries (R1-44)
Unread fan-out on message send SHALL derive per-member counts from a single aggregate fetch of the room's markers (no per-member `findByUserAndRoom` query).

#### Scenario: Send in a large room
- **WHEN** a message is sent in a 1000-member room
- **THEN** unread computation issues a constant number of queries, not ~1000

### Requirement: Catalog member counts via count query (R1-45)
The public-room catalog SHALL obtain member counts with `countByRoom` (or a join projection), never by hydrating member entities.

#### Scenario: Catalog page of large rooms
- **WHEN** a 20-row catalog page of 1000-member rooms is searched
- **THEN** no member entities are hydrated for counting

### Requirement: Sidebar listings batched (R1-46)
Sidebar room/DM listings SHALL batch-fetch unread markers and DM counterpart users in single queries keyed by room id.

#### Scenario: Sidebar refresh with 20 rooms
- **WHEN** the sidebar loads a user's 20 rooms and DMs
- **THEN** the listing runs a constant number of queries, not 40+

### Requirement: FK indexes on hot paths (R1-47)
Indexes SHALL exist on `attachments.message_id`, `messages.reply_to_id`, and `room_invitations.invitee_id`.

#### Scenario: Message delete with replies
- **WHEN** a message referenced by replies is deleted
- **THEN** the `ON DELETE SET NULL` on `reply_to_id` uses an index, not a sequential scan
