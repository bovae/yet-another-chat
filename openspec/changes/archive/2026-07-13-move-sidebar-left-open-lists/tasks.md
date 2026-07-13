# Tasks: Move Sidebar Left, Lists Open by Default

## 1. Sidebar Fragment

- [x] 1.1 In `fragments/sidebar.html`, change fragment signature `sidebar(collapsed)` → `sidebar`, replace the three `th:classappend="${collapsed ? ...}"` conditionals with static `show` on collapse divs / no `collapsed` class on buttons, and `th:attr="aria-expanded=${!collapsed}"` with static `aria-expanded="true"`
- [x] 1.2 Remove duplicate `border-end` class from the fragment root div (`.chat-sidebar` in `chat.css` already draws the right border)

## 2. Layout Reorder

- [x] 2.1 In `chat/index.html`, move the `chat-sidebar` div to be the first child of the flex row (before the center message column) and update the fragment call to `~{fragments/sidebar :: sidebar}`
- [x] 2.2 Same in `chat/room.html`: move `chat-sidebar` div first in the flex row, drop `collapsed=true` from the fragment call

## 3. Verify

- [x] 3.1 Run app, check both views: sidebar left of messages, members panel still right, all three sections expanded, sections still toggle, `?section=private` still opens Private/hides Public
- [x] 3.2 Check mobile (<768px): offcanvas drawer still opens from hamburger, sections expanded
