# Design — Address All R1 Findings

## Context

Spring Boot 3 / Thymeleaf / vanilla-JS chat app; STOMP over WebSocket (simple broker), Postgres (Liquibase formatted SQL), Redis (sessions + presence), local file storage. R1 review (`requirements/improvements-catalog.md`) filed 81 findings across real-time delivery, security, data integrity, performance, UI, and tests. The catalog already names root causes and file/line locations; this design fixes the cross-cutting decisions so the 81 fixes stay coherent.

## Goals / Non-Goals

**Goals:**
- Close every R1 High/Medium/Low finding (R1-01…R1-81).
- Keep fixes minimal and root-caused: shared code paths fixed once, not per-caller.
- No behaviour regressions on API response shapes; tightened authorization returning 403/404 where reads were previously open is intended.

**Non-Goals:**
- R1-77 (Jabber/XMPP): stays descoped; catalog itself recommends out of scope. Mark the checklist item accordingly.
- R1-43 multi-node support: addressed by documenting single-node as a hard constraint, not by introducing a broker relay.
- R2/R3 findings and the UX redesign backlog (separate change).

## Decisions

### D1. Shared broadcast service (R1-03, R1-04, R1-21, R1-57)
Extract a `MessageBroadcastService` (broadcast to `/topic/room.{id}` + unread fan-out) used by `ChatMessageHandler`, `MessageApiController.sendMessage/editMessage/deleteMessage`, and the attachment-upload completion. Event types: existing new-message payload, plus `MESSAGE_EDITED`, `MESSAGE_DELETED` (exists), `MEMBER_JOINED/LEFT/BANNED` on `/topic/room.{id}.events`, and `UNREAD_UPDATE` recompute on delete. *Alternative rejected:* per-controller `convertAndSend` calls — that's what caused the drift.

### D2. Watermark allocation (R1-07)
Native `UPDATE rooms SET next_watermark = next_watermark + 1 WHERE id = :id RETURNING next_watermark` inside the send transaction, plus a unique constraint on `messages(room_id, watermark)` as backstop. *Alternative rejected:* `@Lock(PESSIMISTIC_WRITE)` — holds a row lock across more of the transaction than needed and still lacks the DB backstop.

### D3. History pagination (R1-01, R1-02)
Repository gains a descending query `WHERE room = :room AND watermark < :before ORDER BY watermark DESC LIMIT :n+1`; `before` defaults to +∞ (initial page = newest). Service reverses the page for render order; `has_more` = "older exist". Client "load older" passes the oldest rendered watermark as `before`. The old ascending query stays only for reconnect catch-up (`watermark > cursor`), now looped until `has_more=false` (R1-22).

### D4. STOMP authorization (R1-09, R1-65, R1-62, R1-40)
One inbound `ChannelInterceptor`:
- CONNECT: reject unauthenticated sessions (R1-62).
- SUBSCRIBE `/topic/room.{id}` and `/topic/room.{id}.events`: require membership (PUBLIC rooms: any authenticated user, mirroring history-read rule) (R1-09).
- SUBSCRIBE `/topic/presence.{userId}`: require self, friendship, or shared room (R1-65); same rule in `GET /api/presence`.
`TypingHandler` additionally checks membership before broadcast (R1-40). *Alternative rejected:* Spring's destination `AuthorizationManager` — the pattern-variable extraction plus DB lookup is simpler in a plain interceptor.

### D5. Membership/ownership guards centralized (R1-08, R1-15, R1-25, R1-26, R1-27, R1-32, R1-56)
Reuse the existing `roomMemberService.isMember` / room-visibility check that `ChatWebController.roomView` already applies; apply it in `MessageApiController.getMessages`, `RoomMemberApiController.listMembers`, and `FileStorageService.downloadFile`. Scope checks: attachment→message→room chain verified on download (R1-15) and upload (R1-32); message→room verified on delete (R1-25, same check `ModerationService` already has). `editMessage` and `uploadFile` call the same guard block as `sendMessage` (membership + room-ban + DM-ban + friendship-for-DM, R1-26/27).

### D6. Password reset delivery (R1-10)
Add `spring-boot-starter-mail` + Mailpit container in `docker-compose.yml`. `PasswordService` sends the reset link by mail; web and API controllers always return the generic message and never expose the token. Mail host/port externalized; startup fails fast if mail config missing in non-dev profiles. *Alternative rejected:* logging the link server-side only — catalog explicitly recommends out-of-band delivery and Mailpit is one compose service.

### D7. Attachment safety (R1-13, R1-14, R1-33, R1-34)
- Serve **all** downloads with `Content-Disposition: attachment` built via `ContentDisposition.builder` (UTF-8 filename). `<img>` tags still render attachment-disposition images, so inline chat images keep working; this kills the SVG-XSS vector without a MIME allowlist.
- Filename: `Paths.get(original).getFileName()` + verify `normalize().startsWith(roomDir)`.
- Image-vs-file size cap decided by magic-byte sniffing (stdlib `URLConnection.guessContentTypeFromStream`); client content type ignored for enforcement.

### D8. Schema changeset `004-schema-hardening.sql`
One new Liquibase file: unconditional unique on `rooms(name)` (drop the partial index); unique on `messages(room_id, watermark)`; FK indexes `attachments.message_id`, `messages.reply_to_id`, `room_invitations.invitee_id` (R1-47); `messages.sender_id` and `room_bans.banned_by_id` → `ON DELETE SET NULL` (R1-28). `sender_id` becomes nullable; UI renders "Deleted user" for null senders. Unique-violation → 409 via `DataIntegrityViolationException` handler (R1-29, R1-68, R1-69).

### D9. Unread counting (R1-19, R1-24, R1-44, R1-57, R1-70)
- Marker created at join/DM-create with `lastReadWatermark = nextWatermark - 1` (R1-24).
- `markRoomAsRead` becomes a native upsert `ON CONFLICT ... SET last_read_watermark = GREATEST(...)` (R1-70).
- New `POST /api/rooms/{id}/read` called by the client from `onNewMessage` for the active room (R1-19).
- Fan-out computes counts from one `findByRoom` marker fetch + `count` of undeleted rows per marker bucket — single aggregate query instead of N+1 (R1-44); count actual rows so deletions don't inflate (R1-57), with recompute fan-out on delete.

### D10. Presence (R1-38…42, R1-59, R1-60, R1-61, R1-63, R1-06, R2-01 semantics)
- Redis hash per user keyed by session id: `presence:{userId} = {sessionId: lastBeat+active}`; status = ONLINE if any session active (R1-38).
- `SessionDisconnectEvent` listener removes the session field; last-field removal deletes the key and broadcasts OFFLINE (R1-39, R1-63).
- Sweep uses `SCAN` (R1-59); transition detection uses `ConcurrentHashMap.compute` (R1-61); dead AFK branch removed — client owns the 60s idle rule, and `active:false` within threshold maps ONLINE (R1-60).
- Client: hidden tabs send reduced-rate `active:false` heartbeats (R1-41); activity broadcasts throttled to 1/s (R1-42); `onWebSocketClose` clears the subscription cache so reconnect re-subscribes (R1-06).

### D11. Sessions & credentials (R1-11, R1-35, R1-36, R1-48, R1-66)
Session terminate verifies principal ownership (404 otherwise). `REMEMBER_ME_KEY` validated at startup via `@ConfigurationProperties` validation (no default in `application.yml`; literal only in compose). Password reset/change deletes the user's other Spring Session rows. Login success handler stores `USER_AGENT` + IP session attributes. `@Size(min=8)` on all password DTOs/params.

### D12. Test suite (R1-18, R1-52…54, R1-74, R1-75)
Port the ten uniquely-covered behaviours as plain `@Test`s into existing unit/IT classes first; then delete all 47 `property/` files, jqwik deps, and `.jqwik-database`. Delete the five source-text-asserting tests; convert WS ITs to queue-polling with timeouts; add Content-Disposition assertions to `AttachmentApiIT`; rename misnamed ITs; add CI `timeout-minutes` + concurrency cancel.

### D13. Dev seed (R1-55)
`migrations/005-dev-seed-data.sql`, `context:dev`, fixed UUIDs, precomputed BCrypt literals, `ON CONFLICT DO NOTHING`. Explicit contexts both sides: `spring.liquibase.contexts: prod` (application.yml) / `dev` (application-dev.yml, activated in compose). Avoids the empty-context pitfall that would seed prod and tests.

## Risks / Trade-offs

- [Tightened authorization breaks unknown clients] → Only the reviewed web UI consumes the API; ITs updated alongside. Intended behaviour change, noted in proposal.
- [`sender_id SET NULL` requires null-safe rendering everywhere senders show] → grep all `message.sender` usages; render "Deleted user" fallback in one DTO mapper spot.
- [Always-`attachment` disposition changes UX for opening files in a new tab] → images still render in `<img>`; direct URL opens download instead — acceptable, and it is the catalog's recommended fix.
- [Unconditional unique on `rooms.name` may collide with existing dm-* names] → DM names are deterministic per pair and already unique; verify with a pre-migration duplicate check in the changeset.
- [Heartbeats + interceptor add load on inbound channel] → negligible at 300 users; fan-out already moved to aggregate queries.
- [Large diff spanning ~30 files] → tasks sequenced by capability; each task keeps `./mvnw verify` green.

## Migration Plan

1. Schema changeset 004 ships with code that handles both old and new FK behaviour (SET NULL only relaxes).
2. Seed changeset 005 is dev-context-only; prod contexts set in the same release.
3. Rollback: Liquibase changesets are additive (constraints/indexes) — droppable individually; code rollback = revert commit.

## Open Questions

None — catalog fixes are prescriptive; ambiguities resolved by decisions above.
