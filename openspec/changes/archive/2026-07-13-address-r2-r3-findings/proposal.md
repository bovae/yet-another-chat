# Proposal: address-r2-r3-findings

## Why

Review rounds R2 (presence/social/UX, 2026-07-12) and R3 (navigation/sidebars/test automation, 2026-07-12) left 19 open findings in `requirements/improvements-catalog.md`: 2 High, 7 Medium, 10 Low. The High items are user-visible correctness bugs — a freshly loaded tab reports AFK instead of ONLINE (R2-01, the root of "statuses don't work"), and messages are silently lost when the STOMP socket is down (R2-02). The rest are spec deviations (wireframe layout, admin modal, presence accessibility), navigation gaps, and the user-requested automated Playwright UI suite (R3-01). This change addresses all of them.

## What Changes

**High**
- R2-01: report ONLINE immediately on page load/STOMP connect (client initializes activity timestamp and sends an immediate active heartbeat; page load counts as activity).
- R2-02: never silently drop a message — keep composer text on failure, show connection state, disable Send while disconnected.

**Medium**
- R2-03: push friend-request events over WS (live arrival, panel refresh) and add a pending-count badge to the Friend Requests header.
- R2-04: presence dots get text/tooltip/ARIA (glyph + label), not colour alone.
- R2-05: sidebar and members panel become toggleable off-canvas drawers on mobile instead of `display:none`.
- R3-01: automated Playwright-for-Java E2E suite behind a dedicated Maven profile (user request).
- R3-02: "Add friend"/"Block user" available to every member from the member list (currently admin-gated).
- R3-03: sidebar "Search rooms" discovers catalog rooms via `GET /api/rooms/search` (with join affordance), not just filters joined lists.
- R3-04: sidebar rooms & DMs sorted (recency of last message, fallback name).

**Low**
- R2-06: orphaned DM shows a friendly label instead of raw `dm-{uuid}-{uuid}`.
- R2-07: message action buttons reachable on touch devices (no hover-only reveal).
- R2-08: consolidate admin actions into one tabbed "Manage Room" modal (Members/Admins/Banned/Invitations/Settings per wireframe 4.5).
- R2-09: delete dead `room-item`/`contact-item` fragments.
- R3-05: member list ordered Owner → Admins → Members, then username.
- R3-06: Room info shows an explicit "Owner: <name>" line.
- R3-07: sidebar list fetch errors render an error + retry row, not the empty-state placeholder.
- R3-08: "Sessions" promoted to a top-level nav item.
- R3-09: navbar highlights the active page.
- R3-10: navbar aggregate badge for unread messages + pending friend requests/invitations, live-updated, visible on non-chat pages.

## Capabilities

### New Capabilities
- `ui-e2e-tests`: automated browser E2E coverage (Playwright-for-Java, own Maven profile, two-browser-context scenarios guarding the R1–R3 regressions).

### Modified Capabilities
- `presence`: new requirement — a freshly loaded/focused tab reports ONLINE without user input (R2-01).
- `realtime-delivery`: new requirements — no silent message loss with visible connection state (R2-02); friend-request events delivered live with a pending-count badge (R2-03).
- `ui-gaps`: new requirements covering the remaining UI/navigation findings (R2-04…R2-09, R3-02…R3-10).

All modifications are ADDED requirements; no existing requirement changes behaviour.

## Impact

- **Frontend JS**: `presence.js` (activity init, immediate heartbeat), `stomp-client.js` (connection-state exposure, send result), `app.js` (composer guard, connection banner, touch actions), `sidebar.js` (friend-request refresh + badge, catalog search, error rows, sorting consumption).
- **Templates/CSS**: `fragments/navbar.html` (active state, Sessions link, aggregate badge), `fragments/sidebar.html` (dead fragments removal, drawer markup), `fragments/member-list.html` (member actions un-gating, owner line, presence labels), `fragments/admin-modals.html` (tabbed Manage Room consolidation), `chat.css` (off-canvas drawers, touch action visibility).
- **Backend**: `FriendshipService` (+WS notifications via `SimpMessagingTemplate`), `RoomService.listUserRoomsWithUnread` / `RoomMemberRepository` (sorting), `RoomMemberService.listMembers` (role ordering), `ChatWebController.roomView` (orphaned-DM fallback label), `NotificationService` or a small aggregate-badge endpoint for navbar totals.
- **Build/tests**: new Maven profile `e2e` + `com.microsoft.playwright:playwright` test dependency; new `*E2E` test classes; existing unit/IT suites extended for new backend behaviour.
- **No schema/API breaking changes**; new endpoints are additive (navbar summary; friend-request WS queue events).
