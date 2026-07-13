# data-lifecycle-integrity — Delta Spec

Covers R1-16, R1-17, R1-26…31, R1-68…73.

## ADDED Requirements

### Requirement: Attachment files deleted from disk (R1-16)
Deleting a room, an account (owned rooms), or a message SHALL delete the corresponding attachment files from the file system (room directory recursively on room delete; individual files on message delete), after transaction commit.

#### Scenario: Room deletion removes files
- **WHEN** an owner deletes a room containing uploaded files
- **THEN** `file-storage/{roomId}` no longer exists on disk

### Requirement: Room ban blocks invitation rejoin (R1-17)
Joining a private room via invitation SHALL be denied while the user is on the room's ban list.

#### Scenario: Banned user accepts an invitation
- **WHEN** a room-banned user accepts an invitation to that room
- **THEN** the join is rejected and they remain a non-member

### Requirement: DM sends require active friendship (R1-26)
Sending in a two-person DIRECT chat SHALL require the participants to currently be friends (and neither banned), per spec 2.3.6. Self-DM (Saved Messages) is exempt.

#### Scenario: Message after unfriending
- **WHEN** either user sends a DM after one removed the other as a friend
- **THEN** the send is rejected and the history stays frozen

### Requirement: Edit and upload enforce send-time guards (R1-27)
Message edit and attachment upload SHALL enforce the same membership, room-ban, and DM-ban guards as message send.

#### Scenario: Kicked user edits an old message
- **WHEN** a user kicked from a room edits a message they wrote there
- **THEN** the edit is rejected

### Requirement: Account deletion preserves others' data (R1-28)
Deleting an account SHALL delete only rooms the user owns. Their messages in other rooms SHALL survive as authored by a deleted user (`sender_id SET NULL`), and room bans they issued SHALL remain in force (`banned_by_id SET NULL`).

#### Scenario: Ban survives banner's account deletion
- **WHEN** an admin who banned a user deletes their own account
- **THEN** the banned user still cannot rejoin the room

### Requirement: Room-name uniqueness DB-enforced (R1-29)
The database SHALL enforce unconditional uniqueness of `rooms.name`; unique-violation races SHALL surface as 409, not 500.

#### Scenario: Concurrent creates with the same name
- **WHEN** two requests create rooms with the same name concurrently
- **THEN** one succeeds and the other receives 409

### Requirement: Declined friend requests are re-requestable (R1-30)
Declining a friend request SHALL NOT permanently block future requests between the two users (the declined row is deleted or replaced on the next request).

#### Scenario: Request after a decline
- **WHEN** user A re-sends a request previously declined by user B
- **THEN** B receives a new pending request

### Requirement: Saved Messages detected structurally (R1-31)
Self-DM ("Saved Messages") SHALL be identified structurally (deterministic `saved-messages-{userId}` name or flag), never inferred from member count.

#### Scenario: DM partner deletes their account
- **WHEN** a user's DM counterpart deletes their account
- **THEN** the orphaned DM is not presented as Saved Messages, and a real Saved Messages chat can still be created

### Requirement: DM creation race-safe (R1-68)
Concurrent `getOrCreateDirectChat` calls for the same pair SHALL yield exactly one room, enforced by a DB unique constraint on the deterministic DM name.

#### Scenario: Both users open the DM simultaneously
- **WHEN** two concurrent create calls race
- **THEN** both resolve to the same single DIRECT room

### Requirement: Join/ban races handled (R1-69)
A join concurrent with a ban SHALL NOT leave the banned user a member (ban re-checked in the same transaction), and duplicate concurrent joins SHALL return 409, not 500.

#### Scenario: Join races a ban
- **WHEN** a user's join commits while an admin's ban lands
- **THEN** the final state is banned and not a member

### Requirement: Mark-read is an upsert (R1-70)
`markRoomAsRead` SHALL upsert the marker (`INSERT ... ON CONFLICT ... GREATEST(...)`) so first-read races cannot 500 and the watermark never regresses.

#### Scenario: Two tabs open a never-read room
- **WHEN** both tabs mark the room read concurrently
- **THEN** both requests succeed and one marker row exists

### Requirement: No orphan files on rollback (R1-71)
File-system writes during upload SHALL be reverted when the surrounding transaction rolls back (or the write deferred until after commit).

#### Scenario: DB failure after file write
- **WHEN** the attachment row insert fails after the file was copied
- **THEN** no orphan file remains on disk

### Requirement: Entity equality hygiene (R1-72)
Entity `equals`/`hashCode` SHALL treat unsaved entities (null id) as unequal, use a class-constant hashCode, and SHALL NOT touch lazy associations (composite-key entities compare association ids).

#### Scenario: Two new entities in a Set
- **WHEN** two unsaved entities are added to a HashSet
- **THEN** both are retained

### Requirement: Consistent Spring transactions (R1-73)
All `@Transactional` usage SHALL be Spring's annotation; read-only service paths SHALL be marked `readOnly = true`.

#### Scenario: Read path runs read-only
- **WHEN** `listUserRoomsWithUnread` executes
- **THEN** it runs inside a Spring read-only transaction
