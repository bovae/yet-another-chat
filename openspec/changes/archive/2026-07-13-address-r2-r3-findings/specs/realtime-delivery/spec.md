# realtime-delivery — Delta Spec

Covers R2-02, R2-03.

## ADDED Requirements

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
