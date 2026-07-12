# yet-another-chat — Improvements Catalog

Living register of gaps, bugs, and improvements found during code review. Requirements source: [yet-another-chat.md](./yet-another-chat.md).

**How to use this file**
- Each finding has a stable ID (`R{round}-{nn}`) that is never reused. New reviews append a new round and keep counting; nothing is renumbered.
- Toggle a finding's checkbox in **Progress Checklist** (`[ ]` → `[x]`) when addressed. Keep the same ID in the commit/PR that fixes it.
- **Findings Detail** holds the full write-up (location, symptom, fix) per ID, grouped by theme.
- Severity = highest impact assessed. `verified` = reproduced live in a running instance during review.

---

## Review Rounds

| Round | Date | Scope | Method | IDs | Result |
|---|---|---|---|---|---|
| R1 | 2026-07-12 | Whole project (real-time/WS, security, core backend, web layer, tests/build, requirements coverage) | 6 parallel review agents reading full source + live Playwright/curl two-user session against `mvnw spring-boot:run` | R1-01 … R1-81 | 18 High, 38 Medium, 25 Low |
| R2 | 2026-07-12 | Follow-up: presence display, Saved Messages, friend requests, UUID-in-UI leaks, FE/UX gaps + redesign | FE/UX audit agent + live Playwright two-user session (alice/bob) exercising presence dots, friend-request flow, Saved Messages, DM creation/naming | R2-01 … R2-09 | 2 High, 3 Medium, 4 Low |
| R3 | 2026-07-12 | Navigation bar + left/right sidebars vs wireframe; UI test-automation feasibility | Navigation-surfaces audit agent + live Playwright walkthrough of navbar/profile/sessions pages | R3-01 … R3-10 | 4 Medium, 6 Low |

**R1 headline** — the "live updates unreliable" symptom is not one bug but four, all confirmed:
1. Rooms open on the **oldest** 50 messages, never the newest (R1-01).
2. REST/image-upload sends and message edits **never broadcast** (R1-03, R1-04) — *verified live: a message sent over REST persisted but did not appear in another user's open tab until reload*.
3. Server **STOMP heartbeats are disabled** (`heart-beat:0,0`) so half-open sockets are never detected and never reconnect (R1-05) — *verified in browser console*.
4. Presence subscriptions are **not re-established after reconnect** (R1-06).

**R2 headline** — the "online statuses don't work" complaint is root-caused and *verified live*: a freshly loaded, focused tab shows **AFK**, not ONLINE, and only flips to green after the user physically moves the mouse (R2-01). Friend requests and Saved Messages **do work functionally** (accept persists; Saved Messages sends and labels correctly), but friend requests never arrive live and give no pending-count cue (R2-03). UI labels use real usernames/display names throughout — the *only* raw-UUID leak is the orphaned-DM fallback (R2-06). A silent message-loss path on a dropped socket (R2-02) and mobile panels being fully hidden (R2-05) are the other notable new gaps. Redesign guidance is in **UX Redesign Suggestions** at the end.

**R3 headline** — added an automated-UI-test item (R3-01): Playwright is feasible on this Java/no-Node stack via **Playwright-for-Java** (Maven dep, runs in the JVM), gated behind its own Maven profile so it stays out of the fast build; a manual Playwright workflow already exists at `src/test/e2e/README.md` to seed the scenarios. Navigation findings: the sidebar "Search rooms" box only filters *already-joined* rooms and can't discover catalog rooms (R3-03); per-member "Add friend"/"Block user" is admin-gated so ordinary members can't friend/ban from the user list (R3-02, refines R1-50); rooms/DMs and the member list render unsorted (R3-04, R3-05); and the navbar lacks active-page highlighting and any global unread/request badge (R3-09, R3-10).

---

## Progress Checklist

### High

- [ ] R1-01 — Room opens on the oldest 50 messages, not the newest *(verified)*
- [ ] R1-02 — No descending "before" pagination; "Load older" is broken
- [ ] R1-03 — REST/image-upload message send never broadcast (+ no unread fan-out) *(verified)*
- [ ] R1-04 — Message edit never broadcast (+ client dedup would drop a rebroadcast)
- [ ] R1-05 — Server STOMP heartbeats disabled → dead connections never detected/reconnected *(verified)*
- [ ] R1-06 — Presence subscriptions not re-established after STOMP reconnect
- [ ] R1-07 — Watermark allocation race → duplicate watermarks, dropped messages, wrong unread
- [ ] R1-08 — Message-history REST endpoint has no membership check (IDOR) *(verified reachable)*
- [ ] R1-09 — Any authenticated user can SUBSCRIBE to any room topic (live eavesdrop)
- [ ] R1-10 — Password-reset link shown on the public page → account takeover; API reset is a dead end
- [ ] R1-11 — Session-termination IDOR: any user can log out any session
- [ ] R1-12 — Self-invite into any private room, then accept → join
- [ ] R1-13 — Stored XSS via inline-served SVG/image upload
- [ ] R1-14 — Path traversal / file overwrite via uploaded filename
- [ ] R1-15 — Attachment download not scoped to its room (IDOR)
- [ ] R1-16 — Room/account/message deletion never removes files from disk
- [ ] R1-17 — Banned user can rejoin a private room via invitation
- [ ] R1-18 — Remove all Property-Based Tests (slow); port uniquely-covered behaviour first *(user request)*
- [ ] R2-01 — Freshly loaded/focused tab shows AFK, not ONLINE, until the mouse moves *(verified)*
- [ ] R2-02 — Message silently lost (and input cleared) when the STOMP socket is down

### Medium

- [ ] R1-19 — Unread badge grows for the room the user is actively viewing; no read-ack
- [ ] R1-20 — First message of a new DM/room is dropped by the sidebar → invisible until reload
- [ ] R1-21 — No WS events on join/leave/kick/ban → stale member lists; kicked user keeps working
- [ ] R1-22 — Live message vs missed-message catch-up race → out-of-order; catch-up capped at 100
- [ ] R1-23 — Attachment links in server-rendered history point at a non-existent route (404)
- [ ] R1-24 — Unread count is always 0 for rooms never opened
- [ ] R1-25 — Cross-room message deletion (admin of room A deletes a message in room B)
- [ ] R1-26 — Unfriending doesn't block DM messaging
- [ ] R1-27 — Frozen-DM / lost-room-access not enforced on edit or upload
- [ ] R1-28 — Account deletion wipes the user's messages everywhere and lifts bans they issued
- [ ] R1-29 — Room-name uniqueness not DB-enforced for private rooms; race → dup or 500
- [ ] R1-30 — A declined friend request permanently blocks any future request
- [ ] R1-31 — Orphaned two-person DM misdetected as "Saved Messages"
- [ ] R1-32 — Upload can attach a file to an arbitrary / another user's message
- [ ] R1-33 — Content-Disposition header injection via unsanitized filename
- [ ] R1-34 — 3 MB image cap bypassable via client-supplied content type
- [ ] R1-35 — Weak hardcoded `REMEMBER_ME_KEY` default; no startup validation
- [ ] R1-36 — Password reset/change does not invalidate other sessions
- [ ] R1-37 — Raw exception messages leaked to WS clients
- [ ] R1-38 — Multi-device presence flaps ONLINE↔AFK (last-writer-wins)
- [ ] R1-39 — No presence cleanup on disconnect/logout (`removePresence` is dead code)
- [ ] R1-40 — Typing events have no membership/room check
- [ ] R1-41 — A hidden (minimized) tab goes OFFLINE instead of AFK
- [ ] R1-42 — Every mousemove triggers an unthrottled cross-tab broadcast
- [ ] R1-43 — Simple broker + in-memory presence = single-node only (undocumented ceiling)
- [ ] R1-44 — Unread fan-out is N+1 per member on every send (1000-member room = ~1000 queries)
- [ ] R1-45 — N+1 loading member counts in the room catalog
- [ ] R1-46 — N+1 in sidebar room/DM listings (hottest read path)
- [ ] R1-47 — Missing FK indexes on hot delete/lookup paths
- [ ] R1-48 — Sessions screen shows no browser/IP (`USER_AGENT` is never stored)
- [ ] R1-49 — Attachment comment: no UI to add it, never displayed
- [ ] R1-50 — No UI to send a friend request by username + optional text; sidebar "More" no-ops
- [ ] R1-51 — No UI to remove a friend or view/unban blocked users; no `GET /api/user-bans`
- [ ] R1-52 — Tests assert production *source text* instead of behaviour
- [ ] R1-53 — `Thread.sleep`-based WebSocket tests are slow/flaky
- [ ] R1-54 — Attachment Content-Disposition untested in a real integration test
- [ ] R1-55 — Add a dev-only seed migration (users/chats) for local env *(user request)*
- [ ] R1-56 — Room member-list endpoint has no membership check (private-room enumeration)
- [ ] R2-03 — Friend requests never arrive live and show no pending-count badge *(verified)*
- [ ] R2-04 — Presence conveyed by colour only — no label/tooltip/ARIA (colourblind + screen-reader)
- [ ] R2-05 — Sidebar + members panel fully hidden on mobile; DMs/contacts unreachable
- [ ] R3-01 — Add automated Playwright UI/E2E tests (Playwright-for-Java, own Maven profile) *(user request)*
- [ ] R3-02 — Per-member "Add friend"/"Block user" is admin-gated; members can't friend/ban from the user list
- [ ] R3-03 — Sidebar "Search rooms" only filters joined rooms; can't discover catalog rooms
- [ ] R3-04 — Sidebar rooms & DMs render unsorted (no recency/alphabetical order)

### Low

- [ ] R1-57 — Deleted messages permanently inflate unread counts (no fan-out on delete)
- [ ] R1-58 — `WEBSOCKET_ALLOWED_ORIGINS` not trimmed after split
- [ ] R1-59 — Redis `KEYS` used in the 30s presence sweep
- [ ] R1-60 — Unreachable AFK branch (threshold > TTL)
- [ ] R1-61 — Presence check-then-act race in change detection
- [ ] R1-62 — Unauthenticated clients can hold open WS connections
- [ ] R1-63 — Presence latency exceeds the 2s target
- [ ] R1-64 — Anyone can decline/cancel anyone's invitation
- [ ] R1-65 — Presence subscribable/queryable for any user regardless of relationship
- [ ] R1-66 — Weak/absent password policy (1-char passwords accepted)
- [ ] R1-67 — `GET /api/presence` returns 500 on a malformed UUID; unbounded id list
- [ ] R1-68 — Duplicate DM rooms under concurrent creation
- [ ] R1-69 — Join/ban and concurrent-join races (500 instead of 409)
- [ ] R1-70 — `markRoomAsRead` race on first read (500)
- [ ] R1-71 — File written to disk before the DB row commits (orphan on rollback)
- [ ] R1-72 — Entity `equals`/`hashCode` issues (transient-equal; lazy-field touch)
- [ ] R1-73 — Mixed `jakarta`/Spring `@Transactional`; read paths not `readOnly`
- [ ] R1-74 — Integration tests misnamed `*Test` run in the surefire phase (+ one duplicate)
- [ ] R1-75 — CI has no `timeout-minutes` and no concurrency cancel
- [ ] R1-76 — Sidebar is on the left (spec: right); no accordion compaction on entering a room
- [ ] R1-77 — Jabber/XMPP entirely absent (optional advanced requirement)
- [ ] R1-78 — `favicon.ico` 404 on every page *(verified)*
- [ ] R1-79 — HTMX loaded on every page but never used
- [ ] R1-80 — Dead code: duplicate badge updater, unused cursor state, orphan fragment
- [ ] R1-81 — `/chat` index renders an enabled composer that silently no-ops
- [ ] R2-06 — Orphaned DM leaks a raw `dm-{uuid}-{uuid}` name into title/header/modals
- [ ] R2-07 — Message action buttons (reply/edit/delete) invisible on touch devices
- [ ] R2-08 — Admin actions scattered, not the spec's single tabbed "Manage Room" modal
- [ ] R2-09 — Dead reusable fragments `room-item` / `contact-item`
- [ ] R3-05 — Right-panel member list unsorted (owner/admins not surfaced first)
- [ ] R3-06 — Room-info panel omits the explicit "Owner: <name>" line
- [ ] R3-07 — Sidebar lists fail silently — fetch error leaves the "No rooms/contacts yet" placeholder
- [ ] R3-08 — "Sessions" buried in the Profile dropdown, not a top-level nav item
- [ ] R3-09 — Navbar never highlights the active page
- [ ] R3-10 — No aggregate unread/request badge on the navbar (invisible on non-chat pages)

---

## Findings Detail

### Real-time / WebSocket

**R1-01 · High · Room opens on the oldest 50 messages, not the newest** *(verified)*
`MessageService.getMessageHistory` (`service/MessageService.java:136`) resolves a null cursor to `0` and runs `WHERE watermark > :cursor ORDER BY watermark ASC LIMIT 51` (`repository/MessageRepository.java:25`); `ChatWebController.roomView` (`controller/web/ChatWebController.java:75`) renders that first page. Any room with >50 messages opens on messages 1–50 (ancient history); the newest are absent on load and "Load older" is then a permanent no-op because the oldest rendered watermark is 1 (`static/js/app.js:313`). Single biggest contributor to "messages don't appear".
*Fix:* fetch the latest page for the initial render (order DESC + limit, then reverse) and make `hasMore` mean "older messages exist". Pairs with R1-02.

**R1-02 · High · No descending "before" pagination; infinite scroll broken**
The only history query pages forward (`watermark > cursor ASC`). The client fakes backward paging with `cursor = oldestWatermark - 51` (`static/js/app.js:325,358-365`), which breaks the moment deletions create watermark gaps: the window returns already-rendered rows, `newMessages` is empty, and the load-more button is removed while genuinely older messages remain. `page.has_more` (`app.js:388`) is also semantically "newer than cursor", not "older".
*Fix:* add a real descending `before` cursor param to `GET /api/rooms/{id}/messages` and paginate with it.

**R1-03 · High · REST/image-upload message send is never broadcast** *(verified)*
`MessageApiController.sendMessage` (`controller/api/MessageApiController.java:63-82`) persists the message but performs no `convertAndSend("/topic/room.{id}")` and no unread fan-out — only the WS `/app/chat.send` path (`ws/ChatMessageHandler.java`) does. The image/paste flow uses exactly this REST path (`static/js/app.js:466-517`), and the WS path always sends `attachments: List.of()` (`ChatMessageHandler.java:78`). *Verified live: a REST-sent message persisted and appeared for another user only after a full reload, never live.*
*Fix:* extract broadcast + unread fan-out into a shared service method called by both the WS handler and this controller (and re-broadcast after attachment upload completes).

**R1-04 · High · Message edit is never broadcast**
`MessageApiController.editMessage` (`controller/api/MessageApiController.java:84-96`) returns the updated message to the editor over REST but never broadcasts (deletes do — line 108). Other viewers keep stale text until reload. Additionally `onNewMessage` (`static/js/app.js:250`) drops any payload whose `data-message-id` already exists, so even a re-broadcast of the edited body would be silently discarded.
*Fix:* broadcast a `MESSAGE_EDITED` event and add a client handler that replaces `.message-text` and the "(edited)" marker in place.

**R1-05 · High · Server STOMP heartbeats disabled → dead connections never detected** *(verified)*
`WebSocketConfig` (`config/WebSocketConfig.java:32-36`) calls `enableSimpleBroker("/topic","/queue")` with no `setHeartbeatValue`/`setTaskScheduler`, so the server advertises `heart-beat:0,0` and negotiation zeroes the client's requested 10s/10s (`static/js/stomp-client.js:42`). *Verified in browser console: `<<< CONNECTED heart-beat:0,0`.* After a NAT/proxy idle timeout or laptop sleep the socket goes half-open — the client still reports `connected`, receives nothing, and never reconnects.
*Fix:* `enableSimpleBroker(...).setHeartbeatValue(new long[]{10000,10000}).setTaskScheduler(scheduler)`.

**R1-06 · High · Presence subscriptions not re-established after reconnect**
`presence.js:143-157` caches subscription objects in `presenceSubscriptions[userId]` forever; after a reconnect (`reconnectDelay: 5000`) the broker session is new but `subscribeToFriendPresence` early-returns "already subscribed", so the `onConnect → subscribeToAllVisibleUsers()` call (`stomp-client.js:92`) re-subscribes nothing. One network blip freezes all presence dots until a full reload. `stomp-client.js` also has no disconnect/close hook to clear the cache.
*Fix:* clear the `presenceSubscriptions` map from a `stompClient.onWebSocketClose` hook before `onConnect` re-subscribes.

**R1-07 · High · Watermark allocation race → duplicate watermarks / dropped messages**
`MessageService.java:68-70` does an unguarded read-modify-write of `room.getNextWatermark()` on a `Room` loaded outside the transaction; `Room` has no `@Version` and there is no unique constraint on `(room_id, watermark)` (`migrations/002-schema-changes.sql:29` is a plain index). Two concurrent sends both read N → two messages with watermark N and a lost increment; cursor catch-up (`findByRoomAndWatermarkGreaterThan`) then skips a message and unread counts drift.
*Fix:* allocate atomically (`UPDATE rooms SET next_watermark = next_watermark + 1 ... RETURNING`) or `@Lock(PESSIMISTIC_WRITE)` the room read inside the transaction.

**R1-19 · Medium · Unread badge grows for the actively-viewed room; no read-ack**
`ChatMessageHandler.java:88-96` sends `UNREAD_UPDATE` to every member except the sender — including members currently viewing the room — and `markRoomAsRead` runs only on the page GET (`ChatWebController.java:72`); `onNewMessage` never acknowledges a read. A user watching messages arrive sees that room's sidebar badge climb, and the server-side marker stays stale.
*Fix:* add a mark-read endpoint called from `onNewMessage` for the active room and suppress/reset that room's badge client-side.

**R1-20 · Medium · First message of a new DM/room is dropped → invisible until reload**
`updateUnreadBadge` (`static/js/app.js:271-291`) only decorates an existing `a[href*="/chat/rooms/{id}"]` link. `populateRooms` runs once at load and `YAC.sidebar.refresh` is never invoked from any WS event (`sidebar.js:674`). The first `UNREAD_UPDATE` of a brand-new DM matches no link and vanishes.
*Fix:* when no sidebar link matches `room_id`, call `window.YAC.sidebar.refresh()`.

**R1-21 · Medium · No WS events on join/leave/kick/ban/member changes**
No membership mutation emits anything over WS (only `ChatMessageHandler`, `TypingHandler`, `MessageApiController`, `NotificationService`, `PresenceService` touch `SimpMessagingTemplate`). Member lists go stale for everyone, and a kicked/banned user's UI keeps functioning (and receiving messages — see R1-09) until reload.
*Fix:* broadcast `MEMBER_JOINED/LEFT/BANNED` on `/topic/room.{id}.events` and a user-queue event to the affected user from the membership-mutation services.

**R1-22 · Medium · Live message vs catch-up race → out-of-order render**
On connect the client subscribes first, then REST-fetches the gap (`stomp-client.js:140-172`). A live message arriving during the fetch is `appendChild`-ed immediately and the older missed messages are appended after it. The fetch is capped at `size=100` with no paging loop, so a gap larger than 100 never fills.
*Fix:* insert incoming messages at their watermark-sorted position; loop catch-up pages until `has_more` is false.

**R1-23 · Medium · Attachment links in server-rendered history 404**
`templates/chat/room.html:69-72` builds `/api/rooms/{roomId}/messages/{msgId}/attachments/{attId}/download`, but the only mapped route is `/api/rooms/{roomId}/attachments/{id}/download` (`AttachmentApiController.java:33`, which `app.js:176` uses correctly). Every attachment in server-rendered history is a broken link, while the same message re-rendered by JS works — reads as "flaky" rendering.
*Fix:* correct the template `th:href`/`th:src` to the real route.

**R1-24 · Medium · Unread count always 0 for rooms never opened**
`NotificationService.computeUnreadCount` (`service/NotificationService.java:47-53`) returns 0 when no `UnreadMarker` exists (joined but never opened). New messages in never-opened rooms/DMs raise no badge, and R1-19's fan-out sends 0 to exactly those recipients.
*Fix:* create the marker at join/DM-create time with `lastReadWatermark` = watermark-at-join, instead of returning 0.

**R1-37 · Medium · Raw exception messages leaked to WS clients**
`@MessageExceptionHandler` (`ws/ChatMessageHandler.java:100-115`) sends `ex.getMessage()` of any `Exception` to `/user/queue/errors`, so an unexpected `DataAccessException`/NPE forwards internal detail (SQL, class internals) to the browser; it also logs at WARN with no stack trace.
*Fix:* send a generic message for non-domain exceptions (keep specific text for `Forbidden`/`ResourceNotFound`/validation); log unexpected ones at ERROR with stack trace.

**R1-38 · Medium · Multi-device presence flaps ONLINE↔AFK**
`PresenceService.recordHeartbeat` (`service/PresenceService.java:44-46`) overwrites a single `active` hash field per heartbeat; cross-tab BroadcastChannel coordination only spans one browser. A user active on device A while device B idles gets `active` alternating every 10s, and `broadcastIfChanged` (line 146) spams ONLINE→AFK→ONLINE to all subscribers.
*Fix:* store per-session/device heartbeats (hash field per session id); status = online if any device active.

**R1-39 · Medium · No presence cleanup on disconnect/logout**
`removePresence` (`service/PresenceService.java:138-144`) has zero callers and there is no `SessionDisconnectEvent` listener. After closing the browser or logging out a user stays ONLINE until the 45s Redis TTL + up-to-30s sweep (~75s of phantom presence).
*Fix:* add `@EventListener(SessionDisconnectEvent.class)` that calls `removePresence` when the user's last WS session closes.

**R1-40 · Medium · Typing events have no membership/room check**
`TypingHandler.typing` (`ws/TypingHandler.java:46`) broadcasts a `TYPING` event to `/topic/room.{roomId}.events` for any UUID the client supplies. Any authenticated user can inject typing indicators into rooms they don't belong to.
*Fix:* load the room and verify `roomMemberService.isMember` before broadcasting.

**R1-41 · Medium · Hidden (minimized) tab goes OFFLINE instead of AFK**
`presence.js:223` stops heartbeats on `visibilitychange`; a single minimized/hidden tab lets the Redis key expire after 45s → OFFLINE, though the spec (2.2.3) says offline only when all tabs are closed.
*Fix:* keep reduced-rate heartbeats with `active:false` while hidden.

**R1-42 · Medium · Unthrottled cross-tab broadcast on every mousemove**
`recordCursorActivity` (`presence.js:67-71,219`) posts a BroadcastChannel message (or two localStorage writes in the fallback, each firing `storage` events in every other tab) on every mousemove/keydown — hundreds/sec — for a signal consumed at 2s/60s granularity.
*Fix:* throttle to ~1/sec.

**R1-43 · Medium · Simple broker + in-memory presence = single-node only**
Sessions and presence live in Redis (implying horizontal scale), but the in-memory simple broker (`config/WebSocketConfig.java:33`) and the `lastKnownStatus` `ConcurrentHashMap` (`PresenceService.java:39`) mean a second instance silently drops cross-instance broadcasts and each node's scheduled sweep emits conflicting presence. Fine for 300 users on one node.
*Fix:* document single-node as a hard constraint, or move to a STOMP relay (RabbitMQ/Redis) and move `lastKnownStatus` to Redis before scaling.

**R1-57 · Low · Deleted messages inflate unread counts**
Unread = `nextWatermark - 1 - lastReadWatermark` (`NotificationService.java:55`), which counts deleted messages, and the delete path sends no `UNREAD_UPDATE`. A user whose only unread messages were deleted keeps a nonzero badge until they open the room.
*Fix:* count actual rows (`countByRoomAndWatermarkGreaterThan`) or recompute + fan-out on delete.

**R1-58 · Low · Allowed-origin list not trimmed after split**
`allowedOrigins.split(",")` (`config/WebSocketConfig.java:41`) keeps whitespace, so `"https://a.com, https://b.com"` silently rejects the second origin's handshakes.
*Fix:* split on `\s*,\s*` or `.map(String::trim)`.

**R1-59 · Low · Redis `KEYS` in the 30s presence sweep**
`stringRedisTemplate.keys("presence:*")` (`PresenceService.java:108`) is a blocking O(N) full-keyspace scan against the same Redis holding all HTTP sessions.
*Fix:* use `SCAN` with `ScanOptions`, or maintain a set of active user ids.

**R1-60 · Low · Unreachable AFK branch (threshold > TTL)**
`elapsed > AFK_THRESHOLD_MS` (60s) at `PresenceService.java:88` can never be true because `elapsed > PRESENCE_TTL` (45s) already returned OFFLINE at line 78; effective behaviour is "first `active=false` heartbeat → immediately AFK".
*Fix:* delete the dead branch (client enforces the 60s idle rule) or raise TTL above the AFK threshold if server-side grace is intended.

**R1-61 · Low · Presence check-then-act race**
`lastKnownStatus.get` then `put` (`PresenceService.java:146-152`) is not atomic; concurrent heartbeats or heartbeat-vs-sweep can double-broadcast or drop a transition.
*Fix:* use the return value of `put` (previous mapping) or `compute`.

**R1-62 · Low · Unauthenticated clients can hold open WS connections**
`/ws/**` is `permitAll` at HTTP level (`SecurityConfig.java:37`) and CONNECT is `permitAll` at STOMP level (`MessageSecurityConfig.java:16-21`), so anonymous sockets can connect and idle (they can't SEND/SUBSCRIBE), consuming connection slots.
*Fix:* reject the handshake for unauthenticated sessions via a `HandshakeInterceptor`, or require auth on CONNECT.

**R1-63 · Low · Presence latency exceeds the 2s target**
10s heartbeat interval (`presence.js:6`) + offline detected only via 45s TTL + 30s sweep (`PresenceService.java:28,106`) exceed the 2s propagation target (req 2.7.2 / 3.2).
*Fix:* shorten intervals and/or push explicit disconnect events (see R1-39).

**R1-65 · Low · Presence readable for any user regardless of relationship**
`/topic/presence.{userId}` and `GET /api/presence` (`PresenceApiController.java:19`) require only authentication, so any user can track any user's online/AFK pattern.
*Fix:* restrict presence subscription/query to contacts or co-members.

### Security / Authorization

**R1-08 · High · Message-history REST endpoint has no membership check (IDOR)** *(verified reachable)*
`MessageApiController.getMessages` (`controller/api/MessageApiController.java:48-61`) only resolves the principal; `MessageService.getMessageHistory` has no guard either. Any authenticated user can page the full history of any private room or anyone's DM by UUID. Kicked/banned members retain read access. *During review the endpoint was reachable and returned 201/200 for a freshly created room without a visibility check.*
*Fix:* require membership (allow non-member reads only for PUBLIC rooms) before returning history, mirroring `ChatWebController.roomView`.

**R1-09 · High · Any authenticated user can SUBSCRIBE to any room topic**
`MessageSecurityConfig.java:23` maps `simpSubscribeDestMatchers("/topic/**").authenticated()` with no membership check, so any logged-in user with a room UUID receives all live messages and typing events of any private room or DM. Sends are membership-checked; reads are not.
*Fix:* add a `ChannelInterceptor` (or destination `AuthorizationManager`) validating room membership on SUBSCRIBE frames for `/topic/room.*`.

**R1-10 · High · Password-reset link disclosed on the public page → account takeover**
`AuthWebController.forgotPasswordPost` (`controller/web/AuthWebController.java:75-88`) generates the raw reset token and renders the full `/reset-password?token=...` link into a flash attribute shown on the public forgot-password page (`templates/auth/forgot-password.html:28`). Any anonymous attacker submits a victim's email and reads the working reset link. The API variant (`PasswordApiController.requestPasswordReset:36`) discards the token entirely — there is no mail dependency in `pom.xml` and no mail server in `docker-compose.yml`.
*Fix:* deliver the token out-of-band (add a mail container such as Mailpit/MailHog — one is already running in a sibling compose stack); never render the link; always show the generic success message.

**R1-11 · High · Session-termination IDOR**
`SessionApiController.terminateSession` (`controller/api/SessionApiController.java:30-37`) calls `sessionRepository.deleteById(id)` with no check that the session belongs to the caller (`AuthService.java:88-91`). An attacker who observes/guesses session IDs can forcibly log out arbitrary users.
*Fix:* load the target session, verify its principal name equals `principal.getName()` before deleting; else 404.

**R1-12 · High · Self-invite into any private room**
`RoomInvitationApiController.inviteUser` (`controller/api/RoomInvitationApiController.java:40-63`) never checks that the inviter is a member/admin/owner and accepts an arbitrary `userId`. An attacker POSTs an invitation to any private room with their own userId, then `/{id}/accept` (which only checks invitee==self) and joins. The UI hides the button from non-admins but the API does not.
*Fix:* require the inviter to be an OWNER/ADMIN member of the room.

**R1-13 · High · Stored XSS via inline-served SVG/image upload**
Attachment `contentType` is taken verbatim from the client multipart (`FileStorageService.java:69`, no sniffing) and download serves it `inline` whenever it starts with `image/` (`AttachmentApiController.java:81-87`). An attacker uploads an SVG (`image/svg+xml`) containing `<script>`; a victim opening the download URL executes script in the app origin against their session.
*Fix:* force `Content-Disposition: attachment` for all downloads (or a strict raster-image allowlist) and validate content type server-side.

**R1-14 · High · Path traversal / overwrite via uploaded filename**
`storedFileName = UUID + "_" + originalFileName` (`FileStorageService.java:53-59`) is resolved against the room dir with `REPLACE_EXISTING`; a multipart filename like `a/../../../etc/target` escapes the storage root and can overwrite arbitrary app-writable files.
*Fix:* sanitize to the last path segment (`Paths.get(name).getFileName()`) and verify `targetPath.normalize().startsWith(roomDir)`.

**R1-15 · High · Attachment download not scoped to its room (IDOR)**
`FileStorageService.downloadFile` (`service/FileStorageService.java:81-104`) checks membership of the *path* room but never that the attachment's message belongs to that room. Any user creates a self-DM (member of it) and downloads any attachment system-wide by UUID.
*Fix:* load the attachment first and verify `attachment.getMessage().getRoom().getId().equals(room.getId())`.

**R1-25 · Medium · Cross-room message deletion (IDOR)**
`MessageService.deleteMessage` (`service/MessageService.java:112-133`) loads the message globally and, for the admin path, only checks the caller is ADMIN/OWNER of the room named in the URL — never that the message belongs to that room. An owner of room A deletes any message in room B (IDs harvested via R1-08). `ModerationService.deleteMessage:97` already does this check correctly.
*Fix:* verify `message.getRoom().getId().equals(room.getId())` before authorization/delete.

**R1-32 · Medium · Upload can attach a file to an arbitrary/foreign message**
`FileStorageService.uploadFile` (`service/FileStorageService.java:41-73`, via `AttachmentApiController.java:53`) never verifies the target `messageId` belongs to `roomId` or was authored by the uploader. A member can attach files to another user's message in a different room.
*Fix:* validate `message.room == room` and `message.sender == uploader`.

**R1-33 · Medium · Content-Disposition header injection via filename**
`originalFileName` (attacker-chosen, preserved verbatim) is concatenated into the `Content-Disposition` header inside `filename="..."` with no escaping (`AttachmentApiController.java:85-86`). A `"` or CR/LF breaks out of the quoted value.
*Fix:* build the header with `ContentDisposition.builder(...).filename(name, UTF_8).build()`.

**R1-34 · Medium · 3 MB image cap bypassable via client content type**
`validateFileSize` (`FileStorageService.java:106-121`) branches image-vs-file on `file.getContentType()`, which the client controls. Declaring a >3 MB image as `application/octet-stream` bypasses the image cap up to the 20 MB file cap.
*Fix:* determine media type by content sniffing (magic bytes), not the client header.

**R1-35 · Medium · Weak hardcoded remember-me key default**
The remember-me signing key falls back to the literal `change-me-in-production` when `REMEMBER_ME_KEY` is unset (`application.yml:60`, `SecurityConfig.java:20-21,49-53`). Deployed without the override, the key is public from the repo → forgeable 30-day tokens for any user.
*Fix:* remove the default; fail startup if the key is missing/blank. Keep the literal only in `docker-compose.yml` for local.

**R1-36 · Medium · Password reset/change doesn't invalidate other sessions**
Neither `resetPassword` nor `changePassword` (`service/PasswordService.java:60-101`) invalidates the user's other sessions or remember-me tokens (contrast `UserService.deleteAccount`, which does). After a compromise-driven reset the attacker's existing session/cookie stays valid.
*Fix:* delete all `findByPrincipalName(...)` sessions and rotate the remember-me basis on reset and change.

**R1-56 · Medium · Room member-list endpoint has no membership check**
`RoomMemberApiController.listMembers` (`controller/api/RoomMemberApiController.java:40-50`) resolves user + room but does no membership/visibility check; any authenticated user enumerates usernames, display names, and roles of any private room by UUID.
*Fix:* require membership (or owner/admin).

**R1-64 · Low · Anyone can decline/cancel anyone's invitation**
`RoomInvitationApiController.declineOrCancelInvitation:92` checks neither invitee nor inviter.
*Fix:* restrict to the invitee, the inviter, or a room admin.

**R1-66 · Low · Weak/absent password policy**
Registration reads `password` as a raw `@RequestParam` with no length/complexity check (`AuthWebController.java:49-73`); reset/change DTOs enforce only `@NotBlank`, so 1-char passwords are accepted.
*Fix:* enforce `@Size(min=8)` (and basic complexity) on all password inputs.

**R1-67 · Low · `GET /api/presence` 500 on malformed UUID; unbounded id list**
`PresenceApiController.java:26-37` splits the raw `userIds` and calls `UUID.fromString` in the handler body → `IllegalArgumentException` becomes a 500 (should be 400), with no cap on the number of ids.
*Fix:* bind a validated list (or catch parse errors as 400) and bound the count.

### Data integrity / Domain logic

**R1-16 · High · Room/account/message deletion never removes files from disk**
`RoomService.deleteRoomCascade` (`service/RoomService.java:183-209`) deletes attachment DB rows only; nothing in main code calls `Files.delete` (`FileStorageService` has no delete method). Files under `file-storage/{roomId}` persist forever after room delete, account delete (`UserService.deleteAccount:110`), or message delete (`MessageService.java:130`) — violates 2.4.6 / 2.6.5.
*Fix:* delete the room's storage directory (`FileSystemUtils.deleteRecursively`) after commit in the cascade; delete an attachment's file when its message is deleted.

**R1-17 · High · Banned user can rejoin a private room via invitation**
`RoomMemberService.joinPrivateRoomViaInvitation` (`service/RoomMemberService.java:61-82`) checks the invitation and existing membership but not `roomBanRepository`. Combined with the unauthorized `inviteUser` (R1-12), a room-banned user self-invites and accepts, bypassing 2.4.8 "cannot rejoin unless removed from the ban list".
*Fix:* add a `roomBanRepository.existsByRoomAndUser` guard to the invitation-join path.

**R1-26 · Medium · Unfriending doesn't block DM messaging**
`MessageService.sendMessage`'s DIRECT path checks only user-bans (`checkDirectChatBan`, `MessageService.java:238`); friendship is verified only at DM creation (`DirectChatService.java:42`). After `removeFriend` (no ban) both sides keep messaging — violates 2.3.6.
*Fix:* also require `friendshipService.areFriends(...)` for two-person DIRECT sends.

**R1-27 · Medium · Frozen-DM / lost-room-access not enforced on edit or upload**
`editMessage` (`MessageService.java:90-110`) checks only authorship — no membership, room-ban, or DM-ban check — so kicked/banned users can edit old room messages and a user-banned DM stays editable (violates "history becomes read-only/frozen"). `uploadFile` (`FileStorageService.java:41`) likewise skips the DM-ban check.
*Fix:* reuse the `sendMessage` guards (membership + room-ban + `checkDirectChatBan`) in `editMessage` and `uploadFile`.

**R1-28 · Medium · Account deletion wipes messages everywhere and lifts issued bans**
`userRepository.delete(user)` (`UserService.java:110`) triggers DB cascades: `messages.sender_id ON DELETE CASCADE` and `room_bans.banned_by_id ON DELETE CASCADE` (`migrations/001-init-schema.sql`). All the user's messages in rooms they don't own are deleted (spec: only *owned* rooms are purged), their attachment files orphan on disk, and every room ban they issued disappears → previously banned users silently rejoin (violates 2.1.5 and 3.6).
*Fix:* change `sender_id`/`banned_by_id` to `ON DELETE SET NULL` (keep messages as "deleted user"; keep bans).

**R1-29 · Medium · Room-name uniqueness not DB-enforced for private rooms**
The unique index covers only `visibility = 'PUBLIC'` (`migrations/001-init-schema.sql:24`, `idx_rooms_name_public`), so the check-then-insert in `createRoom`/`updateRoom` (`RoomService.java:49,131`) is the sole guard for private rooms → concurrent creates yield duplicate private names; public-room races surface as a raw 500 instead of 409; flipping private→public can 500 on the partial index.
*Fix:* add an unconditional unique constraint on `rooms(name)` and map `DataIntegrityViolationException` to 409.

**R1-30 · Medium · A declined friend request permanently blocks future requests**
`declineFriendRequest` (`service/FriendshipService.java:93-103`) keeps the row as DECLINED, and `sendFriendRequest` (line 37-41) rejects when any row exists in either direction. After one decline the two users can never request again.
*Fix:* delete the row on decline (or let `sendFriendRequest` replace a DECLINED row).

**R1-31 · Medium · Orphaned two-person DM misdetected as Saved Messages**
`findExistingSavedMessages` (`service/DirectChatService.java:163-177`) returns the first DIRECT room with exactly one member. When a DM partner deletes their account, the surviving DM (one member) becomes the survivor's "Saved Messages", exposing the dead conversation and blocking a real one.
*Fix:* mark self-DMs structurally (match the `saved-messages-{userId}` name or a flag) instead of inferring from member count.

**R1-68 · Low · Duplicate DM rooms under concurrent creation**
Two concurrent `getOrCreateDirectChat(A,B)` both miss `findExistingDirectChat` and create two DIRECT rooms (`DirectChatService.java:37-92`); the deterministic `dm-` name doesn't collide because the unique index only covers PUBLIC rooms.
*Fix:* put the deterministic DM name under a DB unique constraint and catch the violation as "already exists".

**R1-69 · Low · Join/ban and concurrent-join races unguarded**
Check-then-insert without locking (`RoomMemberService.java:35-59`, `ModerationService.java:52-73`): a join concurrent with a ban can insert membership after the ban lands; two concurrent joins surface the PK violation as 500 instead of 409.
*Fix:* re-check the ban after insert in the same transaction; map `DataIntegrityViolationException` to 409.

**R1-70 · Low · `markRoomAsRead` race on first read**
Two tabs opening a never-read room both take the `marker == null` branch (`NotificationService.java:24-45`) and insert the same composite PK — one request dies with a 500; a stale `room.getNextWatermark()` can also regress the watermark.
*Fix:* upsert (`INSERT ... ON CONFLICT ... SET last_read_watermark = GREATEST(...)`).

**R1-71 · Low · File written to disk before the DB row commits**
`Files.copy` (`FileStorageService.java:57-73`) runs before the attachment row is flushed/committed; a rollback afterwards leaves an orphaned file with no DB record.
*Fix:* register a `TransactionSynchronization` to delete the file on rollback, or write after commit.

**R1-72 · Low · Entity `equals`/`hashCode` issues**
Lombok id-only `@EqualsAndHashCode` makes any two unsaved entities equal with identical hashCodes (breaks Set/Map pre-persist); `RoomMember`/`UnreadMarker` `equals` touch lazy `ManyToOne` fields, forcing proxy initialization on comparison.
*Fix:* `equals` returns false when `id == null`; class-based constant `hashCode`; compare association *ids* in composite-key entities.

**R1-73 · Low · Mixed `jakarta`/Spring `@Transactional`; reads not `readOnly`**
`RoomService.java:22` and `UserService.java:17` import `jakarta.transaction.Transactional` (no `readOnly` support) while the rest use Spring's; read methods (`searchCatalog`, `listUserRoomsWithUnread`, `listDirectChats`) run without demarcation.
*Fix:* standardize on `org.springframework.transaction.annotation.Transactional`; mark read paths `readOnly = true`.

### Performance

**R1-44 · Medium · Unread fan-out is N+1 per member on every send**
`ChatMessageHandler.java:88-96` loads all room members then calls `computeUnreadCount` → one `findByUserAndRoom` each (`NotificationService.java:47`). A 1000-member room costs ~1000 queries + 1000 user-queue sends per message, on the limited `clientInboundChannel` pool.
*Fix:* one aggregate query (unread derivable as `nextWatermark-1-lastRead` from a single per-room marker fetch); optionally move fan-out to an async executor after ack.

**R1-45 · Medium · N+1 loading member counts in the room catalog**
`searchCatalog` (`RoomService.java:92-102`) runs `findByRoom(room).size()` per catalog row — with 1000-member rooms and a 20-row page that hydrates ~20k entities per search.
*Fix:* add `long countByRoom(Room room)` (or a join-with-count projection).

**R1-46 · Medium · N+1 in sidebar room/DM listings**
`listUserRoomsWithUnread` (`RoomService.java:152-181`) runs one unread query per room + one member query per DIRECT room; `listDirectChats` (`DirectChatService.java:99-139`) repeats the per-room member query — ~40+ queries per sidebar refresh on the hottest read path.
*Fix:* batch-fetch markers (`findByUser`) and DM counterpart users in single queries keyed by roomId.

**R1-47 · Medium · Missing FK indexes on hot delete/lookup paths**
Postgres does not auto-index FK columns: `attachments.message_id` (every history page), `messages.reply_to_id` (scanned by `ON DELETE SET NULL` on every delete), `room_invitations.invitee_id` all sequential-scan (`migrations/001-init-schema.sql`).
*Fix:* add indexes on those three columns.

### Requirements gaps — UI / features

**R1-48 · Medium · Sessions screen shows no browser/IP**
`AuthService.listSessions` (`service/AuthService.java:57`) reads a session attribute `"USER_AGENT"` that nothing ever sets, so "Browser:" never renders (`templates/profile/sessions.html:39`). Req 2.2.4 wants browser/IP details.
*Fix:* store User-Agent (and IP) at login via a filter/authentication-success handler.

**R1-49 · Medium · Attachment comment: no UI, never displayed**
The server accepts `comment` (`AttachmentApiController.java:48`) but `app.js uploadImage:466` never sends it and neither the template (`chat/room.html:66-75`) nor `createMessageElement` (`app.js:166`) renders `att.comment`. Req 2.6.3.
*Fix:* add a comment input on upload and render it on the attachment.

**R1-50 · Medium · No UI to send a friend request by username + optional text**
Only a per-member "Add friend" exists (`fragments/member-list.html:82`); `app.js addFriend:1026` posts a username only, no `request_text`; sidebar "More" menu items carry no data attributes and silently no-op (`fragments/sidebar.html:84-95`). Req 2.3.2.
*Fix:* add an "add friend by username" form with an optional message field.

**R1-51 · Medium · No UI to remove a friend or view/unban blocked users**
`DELETE /api/friends/{id}` and `DELETE /api/user-bans/{id}` exist but nothing calls them; there is no `GET /api/user-bans` at all, so a user cannot discover the ban id needed to unban (`UserBanApiController`, `UserBanService.listBannedUsers` has no endpoint). Req 2.3.4/2.3.5.
*Fix:* add a ban-list GET endpoint plus contact/ban management UI.

**R1-76 · Low · Sidebar on the left; no accordion compaction**
Rooms/contacts sidebar renders on the left (`chat/room.html:17-20`, `fragments/sidebar.html`) but req 4.1.1 puts it on the right and compacts the room list into an accordion when a room is open.
*Fix:* move the sidebar right and collapse sections on entering a room.

**R1-77 · Low · Jabber/XMPP entirely absent**
No XMPP lib in `pom.xml`, no federation services in `docker-compose.yml`, no Jabber admin UI; explicitly deferred in `.kiro/specs/online-chat-server/tasks.md:370`. Optional advanced requirement (section 6).
*Fix:* out of scope unless the advanced requirement is targeted.

### Frontend hygiene / minor

**R1-78 · Low · `favicon.ico` 404 on every page** *(verified)*
Browser requests `/favicon.ico` → 404 (verified in console). Harmless but noisy.
*Fix:* add a favicon (or a `<link rel="icon">` data URI) in the layout.

**R1-79 · Low · HTMX loaded on every page but never used**
No `hx-*` attribute exists in any template, yet `htmx.min.js` ships on every page (`layout/default.html:22`, `chat/room.html:167`) and `app.js:1086` registers an `htmx:configRequest` CSRF hook that never fires. All navigation is full page loads.
*Fix:* remove the htmx script tags and the `configRequest` hook.

**R1-80 · Low · Dead code**
`YAC.sidebar.updateUnreadBadge`/`refresh` (`sidebar.js:640-659`) duplicate the live copy in `app.js:271` and are never called; `window.YAC_ROOM.nextCursor/hasMore` and `data-next-cursor`/`data-has-more` (`chat/room.html:35`) are written but never read; `fragments/message-item.html` is referenced by nothing (room.html inlines divergent markup, forcing `app.js:713,1211` to probe two DOM shapes).
*Fix:* delete the duplicates and the orphan fragment; keep one message-markup source.

**R1-81 · Low · `/chat` index renders an enabled composer that no-ops**
The message-input fragment (textarea, send, emoji, file buttons) renders on `/chat` where no room exists (`chat/index.html:30`); Send/Enter no-op via the null `getRoomId()` guard, and a picked file would POST to `roomId=null`.
*Fix:* omit the input fragment on the index page.

### Tests / build

**R1-18 · High (user request) · Remove all Property-Based Tests**
47 files, 10,486 lines in `src/test/java/com/bovae/yac/property/` (jqwik 1.9.3 + jqwik-spring). **31 classes boot `@SpringBootTest` + Testcontainers** (Postgres + Redis), 131 `@Property` methods → ~1,277 executions, most doing BCrypt registration and a full-table `deleteAll()` cascade after every try — the dominant build-slowness source. 16 classes are cheap (Mockito/pure). No Cucumber/BDD exists yet; unit + parameterized + 20 integration ITs already cover most behaviour.

*Recommended cut (lazy path):* delete the 31 Spring-booting classes; **first port these uniquely-covered behaviours** as plain `@Test`s into existing unit/IT classes (each is already a 1-example test):
1. Room-update endpoint (`PUT /api/rooms/{id}`): round-trip, non-owner→403, dup-name→409, DIRECT-room guard.
2. Reply-to/quote fields (populate/null, ≤100-char snippet, on edit/send/history/delete incl. lazy-init).
3. `GET /api/presence` batch (only test of that controller).
4. `UNREAD_UPDATE` fan-out to exactly N−1 non-sender members.
5. DM presentation: other-user name in room list, self-DM nulls, new-DM `next_watermark=1`.
6. Friend-request incoming/outgoing PENDING filtering + `request_text` persisted end-to-end.
7. Message-DTO attachment metadata.
8. Room-view controller: mark-read on view, non-member on non-public→403, banned-user cannot view.
9. Misc: leave-DIRECT→403, re-invite-after-decline, display_name→username fallback.
10. camelCase-key-rejected (400) negative API contract.

Then delete jqwik/jqwik-spring deps (`pom.xml:24-25,180-191`), the `.jqwik-database` file, and its `.gitignore` entry. Keep the 9 cheap mock-based classes if desired (rename into `unit/`).

**R1-52 · Medium · Tests assert production source text instead of behaviour**
`integration/BugConditionExplorationIT.java` bug2/3/4/5/7 (lines 140,176,233,274,358) do `Files.readString(Path.of("src/main/java/..."))` and assert string contents of production Java/JS/HTML — breaks on any refactor, passes through behavioural regressions, depends on the working directory.
*Fix:* delete those 5 (behavioural preservation tests already exist); keep bug1/bug6 (real MockMvc tests).

**R1-53 · Medium · `Thread.sleep`-based WebSocket tests**
`integration/WebSocketIT.java` (132,167,186,210,280,309) uses fixed 500–2000 ms sleeps — always slow, flaky under CI load.
*Fix:* poll the existing `BlockingQueue` with a timeout / Awaitility-style await instead of fixed sleeps.

**R1-54 · Medium · Attachment Content-Disposition untested**
The only "test" of inline-vs-attachment disposition is a self-referential PBT; `AttachmentApiIT` asserts no `Content-Disposition` header.
*Fix:* add image + non-image `header().string("Content-Disposition", ...)` assertions to `AttachmentApiIT.downloadFile_asMember_returns200WithContent` (ties to R1-13).

**R1-74 · Low · Integration tests misnamed `*Test` run in the surefire phase**
`RestApiIntegrationTest`, `PresenceNotificationIntegrationTest`, `HealthApiControllerTest` are full `@SpringBootTest`+Testcontainers classes running in the unit phase (failsafe `*IT` binding is otherwise correct). `HealthApiControllerTest` duplicates `WebControllerIT.healthEndpoint_unauthenticated_returns200`.
*Fix:* rename to `*IT`; delete the duplicate `HealthApiControllerTest`.

**R1-75 · Low · CI has no timeout or concurrency cancel**
`.github/workflows/build-and-test.yml:17-32` runs `./mvnw verify` (the whole Testcontainers suite) with no `timeout-minutes` and no `concurrency` group.
*Fix:* add `timeout-minutes` and `concurrency: { group: ..., cancel-in-progress: true }`.

### Enhancements

**R1-55 · Medium (user request) · Dev-only seed migration (users/chats) for local env**
The project uses Liquibase formatted-SQL includes from `db.changelog-master.yaml` (`migrations/001..003`). No contexts are used today and `spring.liquibase.contexts` is unset.
*Recommended mechanism — Liquibase contexts:*
1. Add `migrations/004-dev-seed-data.sql` with `--changeset yac:004-dev-seed context:dev`, inserting users/rooms/room_members with **fixed UUIDs** and `ON CONFLICT DO NOTHING` (idempotent against the persisted `pgdata` volume). Passwords must be **precomputed BCrypt literals** (SQL can't call the encoder).
2. Add a 4th `include` in `db.changelog-master.yaml`.
3. **Pitfall:** Liquibase runs context-tagged changesets when the runtime context list is *empty* — so an untagged setup would seed prod AND every Testcontainers test. Set both sides explicitly: `spring.liquibase.contexts: prod` in `application.yml` and `spring.liquibase.contexts: dev` in a new `application-dev.yml`; activate `dev` for local (`SPRING_PROFILES_ACTIVE=dev` in `docker-compose.yml`, or `-Dspring-boot.run.profiles=dev`).
*Alternative (no pitfall):* `application-dev.yml` overrides `spring.liquibase.change-log` to a `db.changelog-dev.yaml` that includes the base master + the seed file.

---

### Round R2 — Presence, social & UX (2026-07-12)

Follow-up on user-reported breakage in online statuses, Saved Messages, and friend requests, plus a UI/UUID sweep. Verified live with a two-user (alice/bob) Playwright session.

**What was verified working (no finding filed):** friend-request send → the recipient's panel populates with Accept/Decline and **Accept persists** (friendship → ACCEPTED); Saved Messages opens, labels correctly, and sends live; DM creation from a contact opens "Chat with {username}"; the created DM lists in the sidebar as the counterpart's name; page `<title>`s and member/contact lists use real usernames/display names (display_name falls back to username). So the UUID-in-UI surface is small — the only leak is R2-06.

**R2-01 · High · Freshly loaded/focused tab shows AFK, not ONLINE, until the mouse moves** *(verified live)*
On connect, `startHeartbeat` (`static/js/presence.js:127-135`) only schedules the periodic `sendHeartbeat`, which publishes `active: !isAllTabsIdle60s()`. Because `lastActivityTimestamps[myTabId]` initializes to `0` (`presence.js:21`) and no `mousemove`/`keydown`/`focus` has fired yet, the first heartbeat sends `active:false`; the server's `computeStatus` (`service/PresenceService.java:83-100`) maps `active:false` within the 60s window straight to **AFK** (never ONLINE). *Reproduced: a focused, just-loaded tab read `bg-warning` at 4s and 16s with no activity, and only turned `bg-success` after a synthetic mousemove — and `GET /api/presence` confirmed the server itself held the user as AFK.* Violates 2.2.2 ("active in at least one tab → online"); this is the user's "statuses don't work" complaint. Ties R1-60 (server dead-branch), R1-38, R1-41.
*Fix:* initialize `lastActivityTimestamps[myTabId] = Date.now()` and call `sendImmediateActiveHeartbeat()` on STOMP connect; on the server, treat `active:false` within the AFK threshold as ONLINE (AFK only after 60s idle).

**R2-02 · High · Message silently lost (and input cleared) when the STOMP socket is down**
`sendCurrentMessage` (`static/js/app.js:405-431`) calls `stomp.sendMessage(...)` then unconditionally clears the textarea (line 428), but `sendMessage` (`static/js/stomp-client.js:192-206`) only publishes `if (stompClient && stompClient.connected)` — otherwise it silently no-ops. With the heartbeat/reconnect bugs (R1-05/R1-06) making silent disconnects likely and no visible "disconnected" state, the user types, hits Send, the text vanishes, and nothing is sent or queued. No delivery/echo confirmation exists even when connected.
*Fix:* if not connected, keep the text and show an error/retry (or queue and flush on reconnect); add a visible connection state and disable Send when disconnected.

**R2-03 · Medium · Friend requests never arrive live and show no pending-count badge** *(verified live)*
`FriendshipService` emits nothing over WebSocket (only Chat/Typing/Message/Notification/Presence touch the broker), and the sidebar calls `populateFriendRequests()` only once at page init (`static/js/sidebar.js:260-377`). A request sent while the recipient is online is invisible until a manual reload, and the collapsed "▶ Friend Requests" header carries no count badge (unlike Room Invitations, which shows one at `sidebar.js:412`). *Reproduced: bob's request appeared in alice's panel only after a full reload.*
*Fix:* push a user-queue notification on friend-request create/accept and refresh the panel; add a pending-count badge to the header.

**R2-04 · Medium · Presence conveyed by colour only — no label/tooltip/ARIA**
The presence indicator is an 8px `bg-success`/`bg-warning`/`bg-secondary` dot with no `title`, `aria-label`, or adjacent text (`fragments/member-list.html:32-34`, `static/js/sidebar.js:183-186`, `fragments/sidebar.html:108-110`). The wireframe (section 4) shows glyphs `● ◐ ○` plus text `(AFK)`/`(offline)`. Colourblind and screen-reader users cannot distinguish the three states.
*Fix:* add `title`/`aria-label` (and ideally a text suffix) reflecting the status when the dot is recoloured.

**R2-05 · Medium · Sidebar + members panel fully hidden on mobile; DMs/contacts unreachable**
`static/css/chat.css:61-66` — `@media (max-width: 768px) { .chat-sidebar, .chat-members { display:none } }` removes both panels with no hamburger/drawer replacement. Since DMs and contacts live only in the sidebar, they cannot be opened at all on a phone (the navbar only links to the public catalog and `?section=` anchors that scroll a now-hidden list).
*Fix:* make the panels collapsible off-canvas drawers with a toggle instead of `display:none`.

**R2-06 · Low · Orphaned DM leaks a raw `dm-{uuid}-{uuid}` name into title/header/modals**
For a `dm-` room, `ChatWebController.roomView` (`controller/web/ChatWebController.java:81-88`) builds "Chat with {name}" but falls back to `room.getName()` when no other member is found (`.orElse(room.getName())`) — which happens once the DM partner deletes their account (R1-28 cascade). The page `<title>`, chat-header, member-list "Room info", and both admin modals (`chat/room.html:6,26`, `fragments/member-list.html:21`, `fragments/admin-modals.html:62,100`) then show the raw internal name.
*Fix:* fall back to a friendly label ("Direct message" / "Former user") instead of the internal room name.

**R2-07 · Low · Message action buttons invisible on touch devices**
`.message-actions { opacity: 0 }` reveals reply/edit/delete only via `.message-bubble:hover` (`static/css/chat.css:116-123`); devices without hover never see them.
*Fix:* reveal actions on focus/tap, or render them always-visible at reduced emphasis on touch/small screens.

**R2-08 · Low · Admin actions scattered, not the spec's single tabbed "Manage Room" modal**
Requirement 4.5 / the wireframe call for one "Manage Room" modal with `[Members] [Admins] [Banned users] [Invitations] [Settings]` tabs; the implementation uses four separate modals plus an inline per-member dropdown (`fragments/admin-modals.html`, `fragments/member-list.html:94-110`), with no member-search "Members" tab and no dedicated "Admins" view. Functionality mostly exists; the consolidated surface does not.
*Fix:* consolidate the existing modals into one tabbed "Manage Room" dialog.

**R2-09 · Low · Dead reusable fragments `room-item` / `contact-item`**
Both `th:fragment` blocks (`fragments/sidebar.html:99-112`) are defined but never referenced — all sidebar population is done in JS; the `contact-item` dot also lacks `data-user-id`, so it could never receive presence updates. Same theme as R1-80.
*Fix:* delete both fragments.

---

## UX Redesign Suggestions

Best-practice improvements requested by the user, prioritized. These are recommendations rather than defects; where one overlaps a finding, the ID is noted.

**Information hierarchy & layout**
- Move the rooms/contacts sidebar to the **right** and compact open sections into an accordion on room entry (R1-76), making the message column the visual focus per the wireframe; today three fixed-width columns compete equally.
- Give the message column a real empty state ("No messages yet — say hi") and a per-room header showing description + visibility (only the name shows today, `chat/room.html:26`).

**Sidebar / accordion**
- Replace the three always-`collapse show` sections with genuine accordion behaviour (one open at a time while a room is active) and put unread totals on each section header so a collapsed section still signals activity.
- Add a persistent "Add friend by username" affordance (R1-50) and a "Manage contacts" entry for remove/unban (R1-51) instead of the dead "More" dropdown.

**Presence affordances**
- Standardize on the wireframe's glyph + text (`● Alice`, `◐ Carol (AFK)`, `○ Mike (offline)`) so status survives colourblindness and screen readers (R2-04).
- Show the user's own status in the navbar avatar so they get feedback that presence is being reported (helps the confusion caused by R2-01).

**Message list readability**
- Group consecutive messages from the same sender (collapse repeated avatar/name/timestamp) and add day separators; a per-message header on every bubble is noisy in an active room.
- Keep the muted-gray `(edited)` marker (2.5.4) and make reply-quotes clickable to scroll to the referenced message.

**Composer**
- Add a visible connection/sending state (spinner / "Reconnecting…") tied to the STOMP client so the silent-drop path (R2-02) becomes visible; disable Send when disconnected.
- Surface the attachment comment field + preview on upload (R1-49) and show upload progress for large files instead of a frozen UI.

**Modals & feedback**
- Consolidate admin actions into a tabbed "Manage Room" modal (R2-08) and standardize on non-blocking **toasts** for success/error instead of the current mix of a full-screen `errorModal` and inline alerts appended into the message list.
- Add loading skeletons/spinners to sidebar and message fetches (only the banned-users modal shows "Loading…").

**Responsive / mobile**
- Convert the side panels to off-canvas drawers with a toggle (R2-05); ensure touch targets ≥44px and reveal message actions on tap/long-press (R2-07).

**Dark mode & consistency**
- Add a `prefers-color-scheme: dark` theme; colours are currently hardcoded light (`#fff`, `#d1ecf1` in `chat.css`).
- Unify iconography (the emoji-only buttons 😊📎🖼↩✏️🗑 read inconsistently across platforms) into one icon set with `aria-label`s, and standardize badge/button sizing across the sidebar and member panels.

---

### Round R3 — Navigation, sidebars & UI test automation (2026-07-12)

Analysis of the top navbar, left sidebar, and right (members) panel against the wireframe (spec 4.1–4.5), plus feasibility of automated UI tests. Verified via a navigation-surfaces audit and a live Playwright walkthrough (navbar → Public/Private/Contacts, Profile, Sessions).

**Verified working (no finding filed):** Sign out is a proper CSRF-protected `POST /logout` (Thymeleaf `th:action` injects the token); all three nav links + both dropdown links resolve to real controllers; password-change and delete-account are reachable from `/profile`; `/profile/sessions` shows created/last-accessed timestamps, IP, and a Terminate button; logged-out pages use their own navbar with Sign in/Register.

**R3-01 · Medium (user request) · Add automated Playwright UI/E2E tests**
There is no automated UI test coverage — `src/test/e2e/` contains only a manual `README.md` workflow, not wired into the build (see R1-cross-ref: e2e is documentation only). Given the many UI/real-time defects found in R1–R3, an automated browser suite is the highest-value guard.
*Feasibility & recommendation:* the stack is Java/Maven with no Node.js (WebJars), so use **Playwright-for-Java** (`com.microsoft.playwright:playwright`, runs in the JVM, downloads its own browsers) rather than a Node harness — no new runtime is introduced. Put it behind a dedicated Maven profile (e.g. `-Pe2e`) and its own `*E2E`/failsafe binding so it stays out of the fast unit build and the slow default `verify`. Drive a running app (Testcontainers-backed or `docker compose`) and cover the flows the reviews flagged, each doubling as a regression guard for a specific finding:
- register → login → persistent session (R1 auth).
- create room → send a message in **two browser contexts**, assert live receipt (guards R1-01, R1-03, R1-04, R1-05).
- image/paste upload → assert it renders live for the other user (guards R1-03, R1-23).
- message edit/delete → assert propagation (guards R1-04).
- presence: open a tab, assert ONLINE without a manual mouse move (guards R2-01); idle → AFK.
- friend request → assert live arrival + Accept persists (guards R2-03).
- Saved Messages send; DM creation names "Chat with {user}" (guards R2-06 regression).
- attachment download ACL: non-member gets 403 (guards R1-08/R1-15).
- admin actions: kick/ban/unban via the modal.
The existing `src/test/e2e/README.md` scenarios are a ready script to port.

**R3-02 · Medium · Per-member "Add friend"/"Block user" is admin-gated — members can't friend/ban from the user list**
The per-member actions dropdown in `templates/fragments/member-list.html:39-40` (hosting the working `addFriend(this)` and block controls at :77,:85) is wrapped in `th:if="...currentUserRole == 'OWNER' or 'ADMIN'..."`. A plain MEMBER sees no dropdown, so they cannot send a friend request "from the user list in a chat room" (spec 2.3.2) nor user-to-user block (2.3.5); their only fallback is the dead sidebar "More" menu (R1-50). This means R1-50's assumed per-member path is itself unavailable to non-admins.
*Fix:* move "Add friend"/"Block user" out of the admin-gated dropdown into an affordance visible to every member for other users.

**R3-03 · Medium · Sidebar "Search rooms" only filters joined rooms; can't discover catalog rooms**
`setupSearch` (`static/js/sidebar.js:222-256`, input at `fragments/sidebar.html:6`) attaches an `input` listener that only show/hides `<li>`s already loaded in the four sidebar lists via substring match — it never calls `/api/rooms/search` or the catalog. Typing the name of an un-joined public room returns nothing, so the box labelled "Search rooms" can't find rooms to join (catalog search lives only on `/rooms/catalog`).
*Fix:* debounce the input to `GET /api/rooms/search` and render matching public rooms with a join affordance, or relabel the box "Filter my rooms".

**R3-04 · Medium · Sidebar rooms & DMs render unsorted (no recency/alphabetical order)**
`RoomService.listUserRoomsWithUnread` (`service/RoomService.java:152-181`) maps `findByUserWithRoomAndOwner` (no `ORDER BY`, `repository/RoomMemberRepository.java:24-25`) straight to the rendered list, so rooms/DMs appear in membership-insertion order and shift unpredictably between refreshes. With ~20 rooms/user the most recently active conversation can sit anywhere.
*Fix:* sort by last-message timestamp (or at least name) before returning the list.

**R3-05 · Low · Right-panel member list unsorted (owner/admins not surfaced first)**
`RoomMemberService.listMembers` (`service/RoomMemberService.java:102-105`) returns `findByRoomWithUsers` with no `ORDER BY` (`repository/RoomMemberRepository.java:19-20`) and the template renders that order verbatim (`fragments/member-list.html:31`). The wireframe lists Owner → Admins → Members; here the owner can appear anywhere and, near the 1000-member cap, far down the scroll.
*Fix:* order by role (OWNER, ADMIN, MEMBER) then username in the query.

**R3-06 · Low · Room-info panel omits the explicit "Owner: <name>" line**
The "Room info" block (`fragments/member-list.html:20-23`) shows name, description, and a visibility badge only; ownership is conveyed solely by an "Owner" badge next to that member in the list. The wireframe shows a labelled `Owner: alice` line, so a viewer can't see the owner without scanning the member list.
*Fix:* add an `Owner: {room.owner.displayName ?: username}` line to Room info.

**R3-07 · Low · Sidebar lists fail silently — fetch error leaves the "No rooms/contacts yet" placeholder**
`populateRooms`/`populateContacts` (`static/js/sidebar.js:100-102, 215-217`) repopulate only inside the success `.then`; on a failed `/api/rooms/my` or `/api/friends` fetch the `.catch` just `console.error`s, so the server-rendered "No rooms yet"/"No contacts yet" placeholders remain. A user whose data failed to load is wrongly told they have none.
*Fix:* render an explicit error + retry row in the `.catch` handlers.

**R3-08 · Low · "Sessions" buried in the Profile dropdown, not a top-level nav item**
The wireframe top menu lists `... | Contacts | Sessions | Profile ▼ | Sign out`, but `fragments/navbar.html` puts only Public/Private/Contacts at top level (:11-21) and "Sessions" inside the avatar dropdown (:35). Reachable, but a layout deviation.
*Fix:* promote "Sessions" to a top-level `nav-item`.

**R3-09 · Low · Navbar never highlights the active page**
None of the three nav links (`fragments/navbar.html:12-20`) carry a `th:classappend`/`active` state, so the current section is never indicated. *Verified live: no `.nav-link.active` on any page.*
*Fix:* add an `active` class based on the current request URI/section.

**R3-10 · Low · No aggregate unread/request badge on the navbar**
The navbar (`fragments/navbar.html`, whole fragment) carries no badge for unread messages, pending friend requests, or room invitations; those cues exist only in the sidebar, which is absent on `/rooms/catalog`, `/profile`, and `/profile/sessions`. A user on those pages gets no signal that a DM or friend request arrived (spec 2.7.1).
*Fix:* add a navbar badge summing sidebar unread + pending-request counts, pushed over the existing user queue.
