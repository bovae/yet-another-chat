# Move Sidebar Left, Lists Open by Default

## Why

The rooms/contacts sidebar renders on the right, opposite the convention of every mainstream chat app (Slack, Discord, Telegram all navigate from the left), and in the room view all its sections (Public Rooms, Private Rooms, Direct Messages) render collapsed — switching rooms takes an extra click to even see the list.

## What Changes

- Move the rooms/contacts sidebar from the right edge to the left of the message area in both chat views (`chat/index.html`, `chat/room.html`). The members panel stays on the right.
- All sidebar sections (Public Rooms, Private Rooms, Direct Messages) render expanded by default in every view, including the room view (currently collapsed there via `collapsed=true`).
- Remove the now-constant `collapsed` parameter from the sidebar fragment — both callers pass `false`.
- Sections remain individually collapsible by the user (Bootstrap collapse toggles unchanged); `?section=` URL param handling unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ui-gaps`: Requirement "Sidebar on the right with accordion compaction (R1-76)" inverts — the sidebar SHALL render on the left with all sections expanded by default in every view.

## Impact

- Templates: `chat/index.html`, `chat/room.html` (move sidebar div before center content, drop `collapsed` arg), `fragments/sidebar.html` (remove `collapsed` conditionals).
- CSS: `static/css/chat.css` `.chat-sidebar` border stays on the right edge of the panel (correct for left placement); remove the duplicate `border-end` on the inner fragment div.
- JS: none — `sidebar.js` section toggling and `?section=` handling work unchanged.
- Tests: no existing unit/E2E test asserts sidebar position or default collapse state.
