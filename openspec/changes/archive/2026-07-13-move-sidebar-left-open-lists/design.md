# Design: Move Sidebar Left, Lists Open by Default

## Context

Both chat views (`chat/index.html`, `chat/room.html`) lay out a flex row: center message column first, then members panel (`chat-members`, right edge), then rooms/contacts sidebar (`chat-sidebar`) last — so the sidebar lands on the far right. The sidebar content is a shared Thymeleaf fragment `fragments/sidebar.html :: sidebar(collapsed)`; index passes `collapsed=false`, room passes `collapsed=true`. Collapse is plain Bootstrap 5 collapse (no `data-bs-parent`, so sections toggle independently). On <md screens the sidebar is an `offcanvas-start` drawer. `sidebar.js` `handleSectionParam()` reacts to `?section=private|contacts`.

## Goals / Non-Goals

**Goals:**
- Sidebar on the left of the message area in both chat views (desktop, md+).
- All three sections expanded by default in every view.
- Delete the now-dead `collapsed` fragment parameter.

**Non-Goals:**
- No persistence of user collapse state (localStorage) — not requested.
- No change to members panel position, mobile offcanvas behavior (`offcanvas-start` already slides from the left), or `?section=` handling.
- No visual redesign of the sidebar content.

## Decisions

1. **Reorder markup, not CSS `order`.** Move the `chat-sidebar` div to be the first child of the flex row in both templates. Source order = visual order = tab order; no extra CSS. Rejected: `order:-1` — hides layout intent from the template and breaks keyboard tab order.

2. **Hardcode expanded, drop the `collapsed` param.** With both callers passing `false`, the fragment signature becomes `sidebar()` and the three `th:classappend`/`th:attr` conditionals collapse to static `show` / `aria-expanded="true"`. Smaller than keeping a constant-valued parameter.

3. **Border cleanup.** `.chat-sidebar` already has `border-right` (correct for a left-side panel); the inner fragment div's Bootstrap `border-end` duplicates it — remove the class from the fragment.

## Risks / Trade-offs

- [Room view loses "compact" mode; long room lists push contacts below the fold] → sidebar is `overflow-y: auto` and sections stay user-collapsible; acceptable.
- [`?section=private` still auto-hides Public Rooms] → existing intentional focus behavior, kept as-is.
