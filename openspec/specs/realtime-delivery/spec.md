# realtime-delivery Specification

## Purpose

Specification for the `realtime-delivery` capability, established by the `address-r1-findings` change. Covers R1-01…07, R1-19…24, R1-37, R1-57, R1-58, R1-62, R1-63.

## Requirements

### Requirement: Room opens on the newest messages (R1-01)
The initial room view SHALL render the most recent page of messages (newest 50), not the oldest. `has_more` SHALL mean "older messages exist".

#### Scenario: Room with more than 50 messages
- **WHEN** a user opens a room containing 120 messages
- **THEN** messages 71–120 are rendered, with an affordance to load older messages

### Requirement: Descending "before" pagination (R1-02)
`GET /api/rooms/{id}/messages` SHALL accept a `before` watermark cursor returning older messages in descending watermark order, robust to watermark gaps from deletions.

#### Scenario: Load older across a deletion gap
- **WHEN** the client requests messages before watermark 71 and watermarks 60–70 were deleted
- **THEN** the next 50 messages older than 71 are returned and `has_more` reflects whether even older ones exist

### Requirement: Every send path broadcasts (R1-03)
Message persistence via REST (`MessageApiController.sendMessage`) and via attachment upload SHALL trigger the same room-topic broadcast and unread fan-out as the WebSocket send path, through one shared service method.

#### Scenario: REST send appears live
- **WHEN** user A sends a message over REST while user B has the room open
- **THEN** B sees the message without reloading

#### Scenario: Image upload appears live
- **WHEN** user A pastes an image (REST upload flow)
- **THEN** B sees the message with its attachment without reloading

### Requirement: Message edits broadcast (R1-04)
Message edits SHALL broadcast a `MESSAGE_EDITED` event to the room topic; clients SHALL replace the message text and show the "(edited)" marker in place (not drop it as a duplicate).

#### Scenario: Edit propagates live
- **WHEN** user A edits a message while user B views the room
- **THEN** B's rendered message updates in place with an "(edited)" marker

### Requirement: STOMP heartbeats enabled (R1-05)
The server broker SHALL negotiate STOMP heartbeats (10s/10s) so half-open connections are detected and the client reconnect logic triggers.

#### Scenario: Dead connection detected
- **WHEN** the underlying TCP connection goes half-open (e.g. NAT idle timeout)
- **THEN** missed heartbeats close the socket client-side and the client reconnects automatically

### Requirement: Subscriptions re-established after reconnect (R1-06)
The client SHALL clear cached presence/topic subscription state on socket close so that reconnect re-subscribes everything.

#### Scenario: Presence survives a network blip
- **WHEN** the WebSocket drops and reconnects
- **THEN** presence updates for visible users resume without a page reload

### Requirement: Atomic watermark allocation (R1-07)
Watermark allocation SHALL be atomic under concurrent sends (no duplicate watermarks, no lost increments), and the database SHALL enforce uniqueness of `(room_id, watermark)`.

#### Scenario: Concurrent sends
- **WHEN** two messages are sent to the same room concurrently
- **THEN** they receive distinct consecutive watermarks and both are delivered

### Requirement: Read acknowledgement for the active room (R1-19)
A client viewing a room SHALL acknowledge incoming messages via a mark-read endpoint, so the server marker advances and the sidebar badge for that room does not grow.

#### Scenario: Watching a room keeps it read
- **WHEN** a message arrives in the room the user currently views
- **THEN** the room's unread badge stays at zero and the server-side read marker advances

### Requirement: Sidebar picks up brand-new rooms (R1-20)
When an `UNREAD_UPDATE` arrives for a room absent from the sidebar, the client SHALL refresh the sidebar listing.

#### Scenario: First message of a new DM
- **WHEN** a first-ever DM message arrives for a room not yet listed
- **THEN** the sidebar refreshes and shows the new DM with its unread badge

### Requirement: Membership changes broadcast (R1-21)
Join, leave, kick, and ban SHALL emit room-topic events (`MEMBER_JOINED/LEFT/BANNED`) and a user-queue event to the affected user, so member lists update and a kicked/banned user's UI stops functioning without reload.

#### Scenario: Kicked user notified live
- **WHEN** an admin kicks a member who has the room open
- **THEN** other viewers' member lists update and the kicked user's client leaves the room view

### Requirement: Ordered catch-up after reconnect (R1-22)
Missed-message catch-up SHALL page until complete (not cap at 100) and live messages arriving during catch-up SHALL be inserted in watermark order.

#### Scenario: Message arrives during catch-up
- **WHEN** a live message arrives while the client fetches missed messages
- **THEN** the final rendered order is strictly by watermark

### Requirement: Server-rendered attachment links resolve (R1-23)
Attachment links in server-rendered history SHALL use the real download route.

#### Scenario: Download from server-rendered history
- **WHEN** a user clicks an attachment link on a freshly loaded room page
- **THEN** the file downloads (no 404)

### Requirement: Unread counts for never-opened rooms (R1-24)
An unread marker SHALL be created at join/DM-creation time (at the watermark current at join) so rooms never opened still accumulate unread counts.

#### Scenario: Message in a never-opened room
- **WHEN** a user who joined but never opened a room receives a message there
- **THEN** the sidebar badge shows 1

### Requirement: Sanitized WebSocket error messages (R1-37)
Unexpected exceptions in WS handlers SHALL be reported to the client as a generic error (domain/validation errors keep their specific text) and logged at ERROR with a stack trace.

#### Scenario: Unexpected server error during send
- **WHEN** a send fails with an unexpected exception (e.g. `DataAccessException`)
- **THEN** the client receives a generic error message with no internal detail

### Requirement: Deleted messages do not inflate unread counts (R1-57)
Unread counts SHALL reflect actual undeleted messages; message deletion SHALL trigger an unread recompute/fan-out.

#### Scenario: Only unread message deleted
- **WHEN** the single unread message in a room is deleted
- **THEN** the recipient's badge for that room clears without opening the room

### Requirement: Allowed-origin list trimmed (R1-58)
`WEBSOCKET_ALLOWED_ORIGINS` entries SHALL be trimmed after splitting on commas.

#### Scenario: Space after comma
- **WHEN** the variable is `https://a.com, https://b.com`
- **THEN** handshakes from `https://b.com` are accepted

### Requirement: Unauthenticated WebSocket connections rejected (R1-62)
The WS handshake (or CONNECT frame) SHALL be rejected for unauthenticated sessions.

#### Scenario: Anonymous connect
- **WHEN** a client without an authenticated session attempts the WS handshake
- **THEN** the connection is refused

### Requirement: Presence propagation latency (R1-63)
Presence transitions (online/AFK/offline) SHALL propagate to subscribers within the 2-second target, using explicit disconnect events rather than TTL-expiry-only detection.

#### Scenario: Logout propagates quickly
- **WHEN** a user's last WS session disconnects
- **THEN** subscribers see OFFLINE within 2 seconds

### Requirement: No silent message loss on a dead socket (R2-02)
Sending a message while the STOMP connection is down SHALL NOT clear the composer or drop the message. The composer SHALL clear only after the message was actually published; when disconnected, the UI SHALL show a visible connection state (e.g. "Reconnecting…") and disable Send, keeping the typed text intact.

#### Scenario: Send while disconnected
- **WHEN** the STOMP socket is down and the user hits Send
- **THEN** the textarea keeps its content, no message is lost, and the UI indicates the disconnected state

#### Scenario: Connection restored
- **WHEN** the STOMP client reconnects
- **THEN** the connection indicator clears and Send is re-enabled with the drafted text still present

### Requirement: Friend-request events delivered live with a pending badge (R2-03)
Friend-request creation and acceptance SHALL push a notification to the affected user's queue over WebSocket; the recipient's Friend Requests panel SHALL refresh without a reload, and the section header SHALL show a pending-request count badge (like Room Invitations).

#### Scenario: Request arrives live
- **WHEN** user A sends user B a friend request while B is online
- **THEN** B's Friend Requests panel shows the request and the header badge increments without a page reload

#### Scenario: Acceptance propagates
- **WHEN** B accepts the request while A is online
- **THEN** A's contacts list refreshes to include B without a reload
