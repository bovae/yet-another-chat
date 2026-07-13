# Tasks: address-r2-r3-findings

## 1. High — presence bootstrap & message-loss (R2-01, R2-02)

- [x] 1.1 R2-01: initialize `lastActivityTimestamps[myTabId] = Date.now()` in `presence.js`; call `sendImmediateActiveHeartbeat()` from the STOMP `onConnect` hook so a fresh tab reports ONLINE at connect time
- [x] 1.2 R2-02: expose `isConnected()` + `onConnectionChange(cb)` from `stomp-client.js` (wired to `onConnect`/`onWebSocketClose`); make `sendMessage` return success boolean
- [x] 1.3 R2-02: in `app.js`, clear the composer only when send succeeded; keep text otherwise; render a "Reconnecting…" banner and disable Send while disconnected

## 2. Backend — social events, sorting, summary (R2-03, R2-06, R3-04, R3-05, R3-10)

- [x] 2.1 R2-03: publish `FRIEND_REQUEST_CREATED` / `FRIEND_REQUEST_ACCEPTED` user-queue events from `FriendshipService` (same queue mechanism as `UNREAD_UPDATE`); unit/IT coverage for both events
- [x] 2.2 R3-10: add `GET /api/notifications/summary` returning `{unread_total, pending_friend_requests, pending_invitations}` from existing repositories; IT for counts
- [x] 2.3 R3-04: sort sidebar rooms/DMs by last-message recency (name fallback) in `RoomService.listUserRoomsWithUnread`/repository without reintroducing N+1; test asserts ordering
- [x] 2.4 R3-05: order `RoomMemberService.listMembers` OWNER → ADMIN → MEMBER, then username (query-level); test asserts ordering
- [x] 2.5 R2-06: replace the `orElse(room.getName())` fallback for `dm-` rooms with a "Direct message" label in `ChatWebController.roomView` and the DM list mapping; test covers the orphaned-DM case

## 3. Sidebar & navbar (R2-03, R3-03, R3-07, R3-08, R3-09, R3-10, R2-09)

- [x] 3.1 R2-03: client handler for friend-request events — refresh the Friend Requests panel/contacts live and add a pending-count badge to the section header (mirror the Room Invitations badge)
- [x] 3.2 R3-03: debounce the sidebar "Search rooms" input into the catalog search endpoint; render un-joined public rooms in a "Discover" group with a Join affordance (keep existing local filtering)
- [x] 3.3 R3-07: on sidebar fetch failure, render an error + Retry row in the `.catch` handlers instead of leaving the empty-state placeholder
- [x] 3.4 R3-08 + R3-09: add top-level "Sessions" nav item; add active-page highlighting via `th:classappend` on current URI/section
- [x] 3.5 R3-10: navbar aggregate badge — seed from `GET /api/notifications/summary`, live-update from the user queue where STOMP is loaded
- [x] 3.6 R2-09: delete the dead `room-item`/`contact-item` fragments from `fragments/sidebar.html`

## 4. Member list & presence display (R2-04, R3-02, R3-06)

- [x] 4.1 R2-04: add `title`/`aria-label` + glyph/text suffix to every presence dot (member list, sidebar contacts), updated on status change in `presence.js`/`sidebar.js`
- [x] 4.2 R3-02: move "Add friend"/"Block user" out of the admin-gated dropdown so every member sees them for other users (admin-only actions stay gated)
- [x] 4.3 R3-06: add "Owner: <display name ?: username>" line to the Room info panel

## 5. Responsive & modal consolidation (R2-05, R2-07, R2-08)

- [x] 5.1 R2-05: replace the ≤768px `display:none` with Bootstrap off-canvas drawers for sidebar and members panel + toggle buttons in the chat header
- [x] 5.2 R2-07: make message actions visible without hover on `(hover: none)` devices (always-visible at reduced emphasis or tap-to-reveal)
- [x] 5.3 R2-08: consolidate admin modals into one tabbed "Manage Room" modal (Members w/ search, Admins, Banned users, Invitations, Settings); rewire handlers to existing endpoints; delete old modals

## 6. Playwright E2E suite (R3-01)

- [x] 6.1 Add Maven `e2e` profile with `com.microsoft.playwright:playwright` (test scope) and failsafe binding for `**/*E2E.java`; exclude `*E2E` from default surefire/failsafe; document how to run (`docker compose up` + `./mvnw verify -Pe2e`)
- [x] 6.2 E2E base harness: configurable `e2e.baseUrl`, browser/context factory, login helper using seeded dev users
- [x] 6.3 Scenarios: register → login → persistent session; two-context live messaging incl. edit/delete propagation; image upload renders live
- [x] 6.4 Scenarios: presence ONLINE-on-load (guards R2-01) and idle → AFK; friend request arrives live + accept persists (guards R2-03)
- [x] 6.5 Scenarios: Saved Messages + DM naming (guards R2-06); attachment ACL non-member denied; admin kick/ban/unban via the Manage Room modal (guards R2-08)

## 7. Wrap-up

- [x] 7.1 Full `./mvnw verify` green; run `./mvnw verify -Pe2e` against a compose-started app and fix flakes (auto-waiting only, no sleeps)
- [x] 7.2 Tick R2-01…R2-09 and R3-01…R3-10 checkboxes in `requirements/improvements-catalog.md`
