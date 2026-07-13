# ui-gaps — Delta Spec

Covers R1-48…51, R1-76, R1-78…81. (R1-77 Jabber/XMPP is explicitly descoped — see proposal.)

## ADDED Requirements

### Requirement: Sessions screen shows browser and IP (R1-48)
Login SHALL capture the User-Agent and client IP into the session so the sessions screen renders browser/IP details (req 2.2.4).

#### Scenario: Viewing active sessions
- **WHEN** a user opens the sessions screen after logging in from a browser
- **THEN** each session row shows its browser (User-Agent) and IP

### Requirement: Attachment comments end-to-end (R1-49)
Upload SHALL offer an optional comment input, send it with the upload, and the comment SHALL render next to the attachment in both server-rendered and JS-rendered messages (req 2.6.3).

#### Scenario: Upload with a comment
- **WHEN** a user uploads a file with comment "latest requirements"
- **THEN** viewers see the comment on the attachment

### Requirement: Add friend by username with optional text (R1-50)
The UI SHALL provide a form to send a friend request by username with an optional message (`request_text`), replacing the dead sidebar "More" menu items (req 2.3.2).

#### Scenario: Request by username
- **WHEN** a user submits the form with a username and a note
- **THEN** the recipient's pending request includes the note

### Requirement: Contact and ban management UI (R1-51)
The UI SHALL allow removing a friend and viewing/unbanning blocked users, backed by a new `GET /api/user-bans` endpoint listing the caller's bans with their ids (reqs 2.3.4/2.3.5).

#### Scenario: Unban a blocked user
- **WHEN** a user opens their blocked list and clicks Unban
- **THEN** the ban is removed and DMs become possible again (subject to friendship)

### Requirement: Sidebar on the right with accordion compaction (R1-76)
The rooms/contacts sidebar SHALL render on the right and compact the room list into an accordion when a room is open (req 4.1.1).

#### Scenario: Entering a room
- **WHEN** a user opens a room
- **THEN** the sidebar sits on the right with its sections compacted accordion-style

### Requirement: Favicon served (R1-78)
Every page SHALL reference a favicon so no `/favicon.ico` 404s occur.

#### Scenario: Any page load
- **WHEN** a page loads
- **THEN** the browser gets a favicon without a 404

### Requirement: Unused HTMX removed (R1-79)
The HTMX script tags and the `htmx:configRequest` hook SHALL be removed (no `hx-*` attributes exist).

#### Scenario: Page payload
- **WHEN** any page loads
- **THEN** `htmx.min.js` is not requested

### Requirement: Dead frontend code removed (R1-80)
Duplicate `YAC.sidebar.updateUnreadBadge`/`refresh` copies, unused cursor state (`YAC_ROOM.nextCursor/hasMore`, `data-next-cursor`/`data-has-more`), and the orphan `fragments/message-item.html` SHALL be removed, leaving one message-markup source.

#### Scenario: Single markup source
- **WHEN** a message renders via server template or JS
- **THEN** both paths produce the same DOM shape from one source of truth

### Requirement: No composer on the chat index (R1-81)
The `/chat` index page SHALL NOT render an enabled message composer (no room context exists there).

#### Scenario: Visiting /chat
- **WHEN** a user visits the chat index without selecting a room
- **THEN** no message input/send controls are shown
