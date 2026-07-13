# ui-gaps — Delta Spec

Covers R2-04…R2-09, R3-02…R3-10.

## ADDED Requirements

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
