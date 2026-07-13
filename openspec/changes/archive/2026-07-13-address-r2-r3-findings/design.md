# Design: address-r2-r3-findings

## Context

R1 remediation (archived change `address-r1-findings`) already reworked presence server-side (per-session Redis hash, SCAN sweep, disconnect cleanup), extracted `MessageBroadcastService`, and fixed the send/broadcast paths. R2/R3 findings are mostly client-side (presence bootstrap, composer resilience, sidebar/navbar UX), a few small backend additions (friend-request WS push, sorting, aggregate badge), and the new Playwright E2E suite. Catalog line references predate the R1 rework — treat them as pointers, not gospel; the current code has been re-verified where it matters (see below).

Current state verified on `feat-basic-chat-logic`:
- `presence.js` still initializes `lastActivityTimestamps[myTabId] = 0` and only heartbeats ~3s after load (`DOMContentLoaded` + 2s delay, then 1s inside `startHeartbeat`). `sendImmediateActiveHeartbeat()` already exists (used on `visibilitychange`/`focus`) but is not called on load/connect. Server `recordHeartbeat` now falls back `lastActive = now` for a first inactive heartbeat, so the fix is client-side only.
- `stomp-client.js:sendMessage` still silently no-ops when disconnected; `app.js:sendCurrentMessage` clears the textarea unconditionally after calling it.
- `FriendshipService` has no `SimpMessagingTemplate` — no WS events on request/accept.
- `fragments/navbar.html` has no active state, no top-level Sessions, no badge.
- `pom.xml` has no Playwright and no `e2e` profile; `src/test/e2e/` holds only a manual README.

## Goals / Non-Goals

**Goals:** close all 19 open R2/R3 catalog items; keep changes additive (no API/schema breaks); every backend behaviour change covered by unit/IT tests; E2E suite runnable locally and in CI as an opt-in profile.

**Non-Goals:** the UX Redesign Suggestions section of the catalog (dark mode, message grouping, toasts, icon set) — recommendations, not findings; Jabber/XMPP (R1-77, descoped); making E2E part of the default build.

## Decisions

### D1 — R2-01: fix presence bootstrap purely client-side
Initialize `lastActivityTimestamps[myTabId] = Date.now()` (page load is an interaction per spec 2.2.2 reading) and call `sendImmediateActiveHeartbeat()` from the STOMP `onConnect` hook (alongside the existing subscription re-establishment) instead of waiting for the delayed first `sendHeartbeat`. Server already treats a first heartbeat's `lastActive` as `now`; no server change. *Alternative rejected:* server-side "first contact = ONLINE" special-casing — redundant now and hides genuine idle state on reconnect.

### D2 — R2-02: connected-state gating, no offline queue
`stomp-client.js` exposes `isConnected()` plus `onConnectionChange(cb)` (fired from `onConnect`/`onWebSocketClose`), and `sendMessage` returns `true/false`. `app.js` clears the textarea only on `true`; on `false` it keeps the text and shows the connection banner. A slim banner ("Reconnecting…") plus a disabled Send button renders whenever disconnected. *Alternative rejected:* client-side outbox queue flushed on reconnect — duplicate-delivery risk without server-side idempotency keys; keep-text + retry is the smallest honest behaviour and matches the catalog fix.

### D3 — R2-03/R3-10: one user-queue notification channel for social events
`FriendshipService` (and invitation flow if not already) publishes to the existing per-user queue (same mechanism `NotificationService` uses for `UNREAD_UPDATE`) with typed events: `FRIEND_REQUEST_CREATED`, `FRIEND_REQUEST_ACCEPTED`. Client handler refreshes the Friend Requests panel / contacts list and updates badges. The navbar aggregate badge (R3-10) subscribes to the same queue on every authenticated page (navbar fragment loads a small script) and seeds its initial value from a new `GET /api/notifications/summary` returning `{unread_total, pending_friend_requests, pending_invitations}` — computed from existing repositories, no schema change. *Alternative rejected:* polling — WS infra already exists.

### D4 — R3-04: sort in SQL, not JS
Add `ORDER BY` to the membership query (join to last message watermark/`created_at` per room, most recent first, name fallback) or sort in `RoomService.listUserRoomsWithUnread` after the existing batch fetches — whichever avoids a new N+1 (the R1-46 batch work already fetches per-room data usable as the sort key). Member list (R3-05): `ORDER BY role` (OWNER<ADMIN<MEMBER) `, username` in `findByRoomWithUsers`. Server-side ordering keeps JS renderers dumb and covers server-rendered fragments too.

### D5 — R2-05/R2-07: CSS/Bootstrap-native responsive fixes
Sidebar/members panels become Bootstrap off-canvas drawers (`offcanvas-start`/`offcanvas-end`) at ≤768px with toggle buttons in the chat header — Bootstrap is already loaded, no new dependency. Touch action visibility: media-query `(hover: none)` renders `.message-actions` always-visible at reduced opacity; keep hover reveal on desktop.

### D6 — R2-08: consolidate modals, reuse existing endpoints
One `#manageRoomModal` with Bootstrap nav-tabs (Members with search, Admins, Banned users, Invitations, Settings). Existing per-action endpoints and JS handlers are rewired, old modals deleted. No backend change.

### D7 — R3-03: search-box dual behaviour
Keep instant substring filtering of loaded lists; add a debounced (~300ms) call to the existing catalog search endpoint, rendering un-joined public rooms in a "Discover" group with a Join button (reusing the existing join call from the catalog page). *Alternative rejected:* relabel to "Filter my rooms" — cheaper but leaves the discovery gap the finding is actually about.

### D8 — R3-01: Playwright-for-Java behind `-Pe2e`
New Maven profile `e2e` adding `com.microsoft.playwright:playwright` (test scope) and a failsafe execution matching `**/*E2E.java` only in that profile; default surefire/failsafe excludes `*E2E`. Tests drive a running app at a configurable base URL (`-De2e.baseUrl`, default `http://localhost:8080`) — started via `docker compose up` locally or the existing Testcontainers setup; two-user flows use two `BrowserContext`s in one Browser. Seed users come from the existing dev seed migration (R1-55). Scenario list ports `src/test/e2e/README.md` + the catalog's R3-01 list. *Alternative rejected:* Node Playwright — introduces a second runtime on a deliberately Java-only stack.

### D9 — R2-06: friendly fallback label
`ChatWebController.roomView` (and DM list mapping) replaces the `orElse(room.getName())` fallback for `dm-` rooms with a constant label ("Direct message"). One-line-ish fix; no data migration.

## Risks / Trade-offs

- [R2-02 without a queue: a message typed while disconnected still requires a manual re-send] → acceptable; text is preserved and state is visible, which is the finding's actual complaint.
- [Navbar badge adds a WS subscription on every page] → single lightweight queue subscription; pages already load the STOMP client on chat pages — non-chat pages gain one small script; if STOMP is not loaded there, fall back to the summary endpoint value at page load only (badge is best-effort on those pages).
- [R3-04 sort key needs last-message time; a bad join could reintroduce N+1] → compute from data the R1-46 batch queries already fetch, or one aggregate query; assert query count in an IT if practical.
- [Manage Room consolidation touches many JS handlers at once] → keep endpoint contracts untouched; E2E admin scenario (D8) exercises the modal end-to-end.
- [E2E flakiness under CI load] → Playwright auto-waiting assertions only (no sleeps, mirroring R1-53's lesson); profile is opt-in so it can't block the default build while stabilizing.

## Migration Plan

Additive only. Ship order inside the change: backend events/sorting/summary endpoint first (with tests), then client JS/templates, then E2E suite last (it asserts the fixed behaviour). No rollback concerns beyond normal revert.

## Open Questions

None blocking. Whether CI gets a scheduled `-Pe2e` job can be decided after the suite proves stable locally.
