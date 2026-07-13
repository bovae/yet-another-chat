# Tasks — Address All R1 Findings

Each task cites its R1 IDs and the design decision (D#). Keep `./mvnw verify` green after every group; tick the matching checkbox in `requirements/improvements-catalog.md` in the same commit that fixes an ID.

## 1. Schema & config foundation

- [x] 1.1 Add `migrations/004-schema-hardening.sql`: unconditional unique `rooms(name)` (drop partial index, pre-check duplicates), unique `messages(room_id, watermark)`, FK indexes (`attachments.message_id`, `messages.reply_to_id`, `room_invitations.invitee_id`), `messages.sender_id` nullable + `ON DELETE SET NULL`, `room_bans.banned_by_id` → `ON DELETE SET NULL`; register in master changelog (R1-28, R1-29, R1-47, R1-68 backstop; D8)
- [x] 1.2 Map `DataIntegrityViolationException` to 409 in the API exception handler (R1-29, R1-68, R1-69; D8)
- [x] 1.3 Null-safe sender rendering: "Deleted user" fallback in message DTO mapper + templates/JS (R1-28; D8 risk)
- [x] 1.4 Remove `REMEMBER_ME_KEY` default from `application.yml`; validate non-blank at startup; keep literal only in `docker-compose.yml` (R1-35; D11)
- [x] 1.5 Standardize on Spring `@Transactional` (replace jakarta imports in `RoomService`, `UserService`); mark read paths `readOnly = true` (R1-73)
- [x] 1.6 Fix entity `equals`/`hashCode`: null-id unequal, class-constant hashCode, compare association ids in composite-key entities (R1-72)

## 2. Message history & watermark

- [x] 2.1 Atomic watermark allocation via native `UPDATE ... RETURNING` in the send transaction (R1-07; D2)
- [x] 2.2 Add descending `before`-cursor repository query + service support; initial page = newest; `has_more` = "older exist" (R1-01, R1-02; D3)
- [x] 2.3 Update `ChatWebController.roomView` + `GET /api/rooms/{id}/messages` for the new pagination contract (R1-01, R1-02; D3)
- [x] 2.4 Client: render newest page on load, "Load older" passes `before` = oldest rendered watermark (`app.js`) (R1-01, R1-02)
- [x] 2.5 Reconnect catch-up: loop ascending pages until done; insert live messages watermark-sorted (`stomp-client.js`, `app.js`) (R1-22; D3)

## 3. Broadcast unification

- [x] 3.1 Extract `MessageBroadcastService` (room-topic send + unread fan-out) and route `ChatMessageHandler` through it (R1-03; D1)
- [x] 3.2 Call it from `MessageApiController.sendMessage` and after attachment upload completes (R1-03; D1)
- [x] 3.3 Broadcast `MESSAGE_EDITED` from the edit path; client handler replaces text + "(edited)" in place instead of duplicate-dropping (R1-04; D1)
- [x] 3.4 Broadcast `MEMBER_JOINED/LEFT/BANNED` on `/topic/room.{id}.events` + user-queue event to the affected user from membership mutations; client updates member list and evicts kicked/banned user's view (R1-21; D1)
- [x] 3.5 Sanitize WS error handler: generic message for unexpected exceptions, keep domain-error text, log ERROR with stack (R1-37)

## 4. Unread correctness

- [x] 4.1 Create unread marker at join/DM-create with watermark-at-join; remove the return-0 fallback (R1-24; D9)
- [x] 4.2 `markRoomAsRead` → native upsert with `GREATEST` (R1-70; D9)
- [x] 4.3 New `POST /api/rooms/{id}/read`; client calls it from `onNewMessage` for the active room and suppresses that room's badge (R1-19; D9)
- [x] 4.4 Aggregate unread fan-out: one marker fetch + row counts, no per-member queries; count undeleted rows; recompute fan-out on delete (R1-44, R1-57; D9)
- [x] 4.5 Client: unknown `room_id` in `UNREAD_UPDATE` triggers `YAC.sidebar.refresh()` (R1-20)

## 5. WebSocket transport & STOMP authorization

- [x] 5.1 Enable broker heartbeats 10s/10s with a `TaskScheduler` (R1-05; D4)
- [x] 5.2 Inbound `ChannelInterceptor`: reject unauthenticated CONNECT; membership check on `/topic/room.*` SUBSCRIBE; self/friend/co-member check on `/topic/presence.*` SUBSCRIBE (R1-62, R1-09, R1-65; D4)
- [x] 5.3 Apply the same presence-visibility rule in `GET /api/presence`; validate UUIDs as 400 + bound list size (R1-65, R1-67; D4)
- [x] 5.4 Membership check in `TypingHandler` before broadcast (R1-40; D4)
- [x] 5.5 Trim origins after split in `WebSocketConfig` (R1-58)
- [x] 5.6 Client: clear presence-subscription cache in `onWebSocketClose` so reconnect re-subscribes (R1-06; D10)

## 6. Presence service

- [x] 6.1 Per-session Redis hash presence; status = ONLINE if any session active (R1-38; D10)
- [x] 6.2 `SessionDisconnectEvent` listener → remove session field, broadcast OFFLINE on last removal (R1-39, R1-63; D10)
- [x] 6.3 Sweep via `SCAN`; transition detection via `compute`; remove dead AFK branch, `active:false` within threshold = ONLINE (R1-59, R1-61, R1-60; D10)
- [x] 6.4 Client: reduced-rate `active:false` heartbeats while hidden; throttle activity broadcasts to 1/s (R1-41, R1-42; D10)
- [x] 6.5 Document single-node constraint (broker + presence map) in README/ops notes (R1-43)

## 7. REST authorization & session hygiene

- [x] 7.1 Membership/visibility guard on `GET /api/rooms/{id}/messages` (public rooms readable) (R1-08; D5)
- [x] 7.2 Membership guard on room member-list endpoint (R1-56; D5)
- [x] 7.3 Session terminate verifies principal ownership; else 404 (R1-11; D11)
- [x] 7.4 Invitation create requires OWNER/ADMIN inviter; decline/cancel restricted to invitee/inviter/admin (R1-12, R1-64)
- [x] 7.5 Room-message delete verifies message.room == path room (R1-25; D5)
- [x] 7.6 Password reset/change invalidates the user's other sessions + remember-me (R1-36; D11)
- [x] 7.7 `@Size(min=8)` on all password inputs (registration param + DTOs) (R1-66; D11)
- [x] 7.8 Login success handler stores User-Agent + IP; sessions screen renders them (R1-48; D11)

## 8. Password-reset delivery

- [x] 8.1 Add `spring-boot-starter-mail` + Mailpit service in `docker-compose.yml`; externalize mail config (R1-10; D6)
- [x] 8.2 Send reset link by mail from `PasswordService`; web + API always return generic message; API path actually triggers the mail (R1-10; D6)

## 9. Attachments & files

- [x] 9.1 Sanitize filename to last segment; verify normalized path stays in room dir (R1-14; D7)
- [x] 9.2 All downloads `Content-Disposition: attachment` via `ContentDisposition.builder` UTF-8 (R1-13, R1-33; D7)
- [x] 9.3 Size caps by magic-byte sniffing, ignore client content type (R1-34; D7)
- [x] 9.4 Download verifies attachment→message→room chain (R1-15; D5); upload verifies message∈room ∧ author=uploader (R1-32; D5)
- [x] 9.5 Upload/edit enforce send-time guards (membership, room-ban, DM-ban) (R1-27; D5)
- [x] 9.6 Delete files from disk after commit: room dir on room/account-owned-room delete, files on message delete (R1-16); rollback cleanup for orphan uploads via `TransactionSynchronization` (R1-71)
- [x] 9.7 Fix server-rendered attachment link route in `chat/room.html` (R1-23)

## 10. Domain rules & races

- [x] 10.1 Ban check in `joinPrivateRoomViaInvitation` (R1-17)
- [x] 10.2 Friendship required for two-person DIRECT sends (self-DM exempt) (R1-26; D5)
- [x] 10.3 Delete friendship row on decline (or replace on re-request) (R1-30)
- [x] 10.4 Structural Saved-Messages detection (`saved-messages-{userId}` name), not member-count inference (R1-31)
- [x] 10.5 DM-create race resolved by unique constraint + catch-as-exists (R1-68); join/ban race re-check + 409 mapping (R1-69)
- [x] 10.6 Account deletion: verify SET-NULL semantics keep messages/bans; owned-room file cleanup ties to 9.6 (R1-28)

## 11. Query performance

- [x] 11.1 `countByRoom` for catalog member counts (R1-45)
- [x] 11.2 Batch sidebar listings: markers via `findByUser`, DM counterparts in one query (R1-46)

## 12. UI gaps & frontend hygiene

- [x] 12.1 Attachment comment: input on upload, sent to API, rendered in both template and JS paths (R1-49)
- [x] 12.2 Add-friend-by-username form with optional `request_text`; remove dead "More" menu items (R1-50)
- [x] 12.3 `GET /api/user-bans` endpoint + contacts management UI (remove friend, view/unban blocked) (R1-51)
- [x] 12.4 Move sidebar to the right; accordion compaction on room entry (R1-76)
- [x] 12.5 Favicon in layout (R1-78)
- [x] 12.6 Remove HTMX script tags + `configRequest` hook (R1-79)
- [x] 12.7 Remove dead code: duplicate sidebar badge/refresh, unused cursor state/data-attrs, orphan `message-item.html`; single message-markup source (R1-80)
- [x] 12.8 Omit composer fragment on `/chat` index (R1-81)

## 13. Test suite & CI

- [x] 13.1 Port the 10 uniquely-covered PBT behaviours as plain `@Test`s into existing unit/IT classes (R1-18; D12)
- [x] 13.2 Delete `src/test/java/com/bovae/yac/property/`, jqwik deps in `pom.xml`, `.jqwik-database` + gitignore entry (R1-18; D12)
- [x] 13.3 Delete the 5 source-text-asserting tests in `BugConditionExplorationIT` (keep bug1/bug6) (R1-52)
- [x] 13.4 Replace `Thread.sleep` in `WebSocketIT` with queue polling/timeouts (R1-53)
- [x] 13.5 Add image + non-image `Content-Disposition` assertions to `AttachmentApiIT` (R1-54)
- [x] 13.6 Rename `RestApiIntegrationTest`/`PresenceNotificationIntegrationTest` → `*IT`; delete duplicate `HealthApiControllerTest` (R1-74)
- [x] 13.7 CI: `timeout-minutes` + `concurrency` with `cancel-in-progress` (R1-75)
- [x] 13.8 New/updated tests for the security guards (history/member-list/session/invite/attachment IDORs, traversal, size-cap sniffing) and pagination/broadcast behaviour — cover each fixed R1-High with at least one behavioural test

## 14. Dev seed & wrap-up

- [x] 14.1 `migrations/005-dev-seed-data.sql` (`context:dev`, fixed UUIDs, BCrypt literals, `ON CONFLICT DO NOTHING`); `spring.liquibase.contexts: prod` in `application.yml`, `dev` in new `application-dev.yml`, activated in compose (R1-55; D13)
- [x] 14.2 Mark R1-77 (Jabber) as explicitly descoped in the catalog; tick all fixed R1 checkboxes in `requirements/improvements-catalog.md`
- [ ] 14.3 Full `./mvnw verify` + manual two-browser smoke (live send/edit, presence, reconnect) before PR
