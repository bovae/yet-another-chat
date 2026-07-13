# ui-gaps Specification

## Purpose

Specification for the `ui-gaps` capability, established by the `address-r1-findings` change. Covers R1-48…51, R1-76, R1-78…81. (R1-77 Jabber/XMPP is explicitly descoped — see proposal.)

## Requirements

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
The rooms/contacts sidebar SHALL render on the left of the message area, with all sections (Public Rooms, Private Rooms, Direct Messages) expanded by default in every chat view. Sections remain individually collapsible by the user.

#### Scenario: Entering a room
- **WHEN** a user opens a room
- **THEN** the sidebar sits on the left with all its sections expanded

#### Scenario: Opening the chat index
- **WHEN** a user opens the chat index page
- **THEN** the sidebar sits on the left with all its sections expanded

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

### Requirement: Presence is not conveyed by colour alone (R2-04)
Every presence indicator (member list, sidebar contacts) SHALL carry a text affordance — `title` tooltip, `aria-label`, and/or a visible glyph/text suffix per the wireframe (`● Alice`, `◐ Carol (AFK)`, `○ Mike (offline)`) — updated whenever the status changes.

#### Scenario: Screen reader reads a contact
- **WHEN** a screen reader focuses a contact whose status is AFK
- **THEN** the announced text includes the status (e.g. "Carol, AFK"), not just a coloured dot

### Requirement: Sidebar and members panel reachable on mobile (R2-05)
On small screens the rooms/contacts sidebar and the members panel SHALL be presented as toggleable off-canvas drawers instead of `display:none`, so DMs and contacts remain reachable on a phone.

#### Scenario: Opening contacts on a phone
- **WHEN** a user on a ≤768px viewport taps the sidebar toggle
- **THEN** the rooms/contacts drawer slides in and any contact/DM can be opened

### Requirement: Orphaned DM shows a friendly label (R2-06)
When a DIRECT room's counterpart cannot be resolved (e.g. deleted account), the UI SHALL fall back to a friendly label (e.g. "Direct message") in the page title, chat header, room info, and modals — never the raw internal `dm-{uuid}-{uuid}` room name.

#### Scenario: DM partner deleted their account
- **WHEN** a user opens a DM whose other participant no longer exists
- **THEN** the title/header show a friendly label with no raw UUID visible

### Requirement: Message actions usable on touch devices (R2-07)
Message action buttons (reply/edit/delete) SHALL be reachable without hover: revealed on tap/focus or rendered always-visible at reduced emphasis on touch/small screens.

#### Scenario: Replying from a phone
- **WHEN** a touch-device user taps a message bubble
- **THEN** the reply/edit/delete actions become visible and usable

### Requirement: Single tabbed "Manage Room" modal (R2-08)
Admin actions SHALL be consolidated into one "Manage Room" modal with Members, Admins, Banned users, Invitations, and Settings tabs (wireframe 4.5), including member search on the Members tab, replacing the separate scattered modals.

#### Scenario: Admin manages the room
- **WHEN** an admin opens "Manage Room"
- **THEN** one modal offers all five tabs and every existing admin action (ban/unban, remove member, manage admins, invitations, room settings) works from it

### Requirement: Dead sidebar fragments removed (R2-09)
The unused `room-item` and `contact-item` Thymeleaf fragments SHALL be deleted; sidebar markup has a single source of truth in the JS renderers.

#### Scenario: Searching templates for the fragments
- **WHEN** templates are searched for `room-item`/`contact-item` fragment definitions
- **THEN** none exist and no template references them

### Requirement: Friend/block actions available to every member (R3-02)
Per-member "Add friend" and "Block user" actions SHALL be visible to every room member (for members other than themselves), not gated behind the admin dropdown, satisfying "send a friend request from the user list in a chat room" (spec 2.3.2) and user-to-user ban (2.3.5).

#### Scenario: Plain member befriends from the member list
- **WHEN** a non-admin member opens the member list and picks another member
- **THEN** "Add friend" and "Block user" actions are available and work

### Requirement: Sidebar room search discovers catalog rooms (R3-03)
The sidebar "Search rooms" input SHALL query the public-room catalog (`GET /api/rooms/search`, debounced) and render matching un-joined public rooms with a join affordance, in addition to filtering already-joined lists.

#### Scenario: Finding an un-joined public room
- **WHEN** a user types the name of a public room they haven't joined
- **THEN** the room appears in the results with a Join action

### Requirement: Sidebar rooms and DMs are sorted (R3-04)
Sidebar room and DM lists SHALL be ordered by recency of last message (most recent first), falling back to name, so ordering is stable across refreshes.

#### Scenario: Recently active conversation surfaces first
- **WHEN** a room receives the newest message among a user's rooms
- **THEN** it appears at the top of the sidebar list after the next refresh

### Requirement: Member list ordered by role (R3-05)
The room member list SHALL be ordered Owner first, then Admins, then Members, each group sorted by username (wireframe 4.1.1).

#### Scenario: Viewing a large room
- **WHEN** a user opens the member panel of a room
- **THEN** the owner is listed first, admins next, members after — each alphabetically

### Requirement: Room info shows the owner (R3-06)
The Room info panel SHALL include an explicit "Owner: <display name or username>" line.

#### Scenario: Checking room ownership
- **WHEN** a user opens a room
- **THEN** Room info shows "Owner: <name>" without scanning the member list

### Requirement: Sidebar fetch failures are visible (R3-07)
When a sidebar list fetch (`/api/rooms/my`, `/api/friends`, etc.) fails, the UI SHALL replace the list content with an explicit error state and a retry affordance — never leave the "No rooms/contacts yet" empty-state placeholder.

#### Scenario: API error while loading contacts
- **WHEN** the contacts fetch returns an error
- **THEN** the contacts section shows an error message with a Retry control instead of "No contacts yet"

### Requirement: Sessions is a top-level nav item (R3-08)
"Sessions" SHALL appear as a top-level navbar item (wireframe top menu), in addition to remaining reachable from the profile dropdown.

#### Scenario: Navigating to sessions
- **WHEN** an authenticated user views any page
- **THEN** the navbar shows a top-level "Sessions" link to `/profile/sessions`

### Requirement: Navbar highlights the active page (R3-09)
The navbar SHALL mark the nav item matching the current page/section with an active state.

#### Scenario: On the public catalog
- **WHEN** a user is on `/rooms/catalog`
- **THEN** the "Public Rooms" nav link carries the active styling and no other link does

### Requirement: Navbar aggregate notification badge (R3-10)
The navbar SHALL show an aggregate badge combining unread messages, pending friend requests, and pending room invitations, updated live over the user queue, so activity is visible on pages without the sidebar (spec 2.7.1).

#### Scenario: DM arrives while browsing the catalog
- **WHEN** a user on `/rooms/catalog` receives a new DM
- **THEN** the navbar badge appears/increments without a reload
