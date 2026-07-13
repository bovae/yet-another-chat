# Address All R1 Review Findings

## Why

Round-1 review of the chat application (`requirements/improvements-catalog.md`) filed 81 findings (R1-01 … R1-81): 18 High, 38 Medium, 25 Low. Several are verified-live product breakers ("live updates unreliable" = R1-01/03/04/05/06) and exploitable security holes (IDORs, stored XSS, path traversal, account takeover via public reset link). This change addresses every R1 item so the app is functionally correct, secure, and the build is fast.

## What Changes

- **Real-time delivery fixed**: rooms open on newest messages with real backward pagination; REST/upload sends and edits broadcast to all viewers; STOMP heartbeats enabled; presence subscriptions survive reconnect; watermark allocation made atomic; unread badges accurate (read-ack, new-DM refresh, never-opened rooms, deletes).
- **Security hardening**: membership checks on message history, room topics (SUBSCRIBE), member lists, typing events; session-termination and attachment IDORs closed; self-invite and banned-rejoin closed; reset link no longer rendered publicly (delivered via local mail container); SVG XSS, path traversal, header injection, size-cap bypass fixed; remember-me key required at startup; password reset invalidates sessions; password policy enforced.
- **Data lifecycle & integrity**: attachment files deleted from disk with rooms/messages/accounts; account deletion keeps others' history (`SET NULL` instead of cascade); unfriend blocks DM sends; frozen-DM/lost-access enforced on edit and upload; DB uniqueness for room names and DM rooms; declined friend requests re-requestable; Saved Messages detected structurally; races (join/ban, mark-read, DM create, upload rollback) closed; entity `equals`/`hashCode` and `@Transactional` usage fixed.
- **Performance**: unread fan-out, catalog member counts, and sidebar listings de-N+1'd; missing FK indexes added.
- **UI gaps**: sessions screen shows browser/IP; attachment comment UI; add-friend-by-username form; friend-remove and ban-list management UI (+ `GET /api/user-bans`); sidebar moved right with accordion compaction; favicon; dead code/HTMX removed; no-op composer removed from `/chat` index.
- **Tests & build**: all Spring-booting property-based tests removed after porting uniquely-covered behaviours as plain tests; source-text-asserting tests deleted; `Thread.sleep` WS tests made event-driven; Content-Disposition integration assertions added; misnamed `*Test` ITs renamed; CI timeout + concurrency cancel.
- **Dev tooling**: dev-only Liquibase seed migration (users/rooms) gated by explicit `dev`/`prod` contexts.
- **Explicitly descoped**: R1-77 (Jabber/XMPP) stays out of scope per the catalog's own recommendation; R1-43 is addressed by documenting the single-node constraint, not by adding a broker relay.

## Capabilities

### New Capabilities

- `realtime-delivery`: message history pagination, broadcast on all send/edit paths, STOMP heartbeat/reconnect resilience, watermark integrity, unread-count correctness (R1-01…07, 19–24, 37, 57, 58, 62, 63).
- `presence`: multi-device presence aggregation, disconnect cleanup, typing-event authorization, throttled activity tracking, sweep efficiency, single-node constraint documented (R1-38…43, 59, 60, 61, 65).
- `authorization-hardening`: membership/ownership checks on REST + STOMP surfaces, upload/download safety, credential and session hygiene (R1-08…15, 25, 32–36, 40, 56, 64, 66, 67).
- `data-lifecycle-integrity`: file cleanup on delete, account-deletion semantics, relationship rules on DM messaging, DB-level uniqueness, concurrency races, entity/transaction hygiene (R1-16, 17, 26–31, 68–73).
- `query-performance`: aggregate unread fan-out, count queries, batched sidebar reads, FK indexes (R1-44…47).
- `ui-gaps`: sessions detail, attachment comments, contact management UI, layout per wireframe, frontend hygiene (R1-48…51, 76, 78–81).
- `test-build-hygiene`: PBT removal with behaviour porting, behavioural (not source-text) assertions, deterministic WS tests, correct surefire/failsafe phases, CI guards (R1-18, 52–54, 74, 75).
- `dev-seed-data`: idempotent dev-context seed migration for local runs (R1-55).

### Modified Capabilities

None — no specs exist yet in `openspec/specs/`.

## Impact

- **Backend**: `MessageService`, `MessageApiController`, `ChatMessageHandler`, `TypingHandler`, `NotificationService`, `PresenceService`, `FileStorageService`, `AttachmentApiController`, `SessionApiController`, `RoomInvitationApiController`, `RoomMemberService`, `RoomService`, `DirectChatService`, `FriendshipService`, `UserService`, `PasswordService`, `AuthService`, `ModerationService`, `SecurityConfig`, `WebSocketConfig`, `MessageSecurityConfig`, repositories.
- **Schema**: new Liquibase changesets — unique constraints (`rooms.name`, `(room_id, watermark)`, DM name), FK indexes, `ON DELETE SET NULL` for `messages.sender_id`/`room_bans.banned_by_id`, dev seed.
- **Frontend**: `app.js`, `stomp-client.js`, `presence.js`, `sidebar.js`, templates (`chat/room.html`, fragments, layout), `chat.css`.
- **Build/infra**: `pom.xml` (drop jqwik, add mail sender), `docker-compose.yml` (Mailpit), CI workflow, test suite reshuffle (~10k lines of PBT deleted).
- **API**: new `GET /api/user-bans`, mark-read endpoint, `before` cursor param on message history. Existing response shapes unchanged; some previously-open endpoints now return 403/404 for non-members (intended).
