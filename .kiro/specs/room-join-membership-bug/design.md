# Room Join Membership Bug — Bugfix Design

## Overview

The "Join" button in the public room catalog (`/rooms/catalog`) is a plain `<a>` tag linking directly to `/chat/rooms/{id}`. No membership is created, so non-member users land on the chat view without a `RoomMember` record and cannot send messages.

The fix uses a two-pronged approach:

1. **Catalog "Join" button**: Change the plain `<a>` link to a form POST (via HTMX or standard form) that hits a new `POST /rooms/{id}/join` endpoint in `RoomWebController`. This endpoint calls `RoomMemberService.joinPublicRoom()` and then redirects to `/chat/rooms/{id}`. The user becomes a member *before* seeing the chat view.

2. **Non-member room view**: When a non-member navigates directly to `/chat/rooms/{id}` for a public room (e.g., via URL bar or shared link), the system does NOT auto-join them. Instead, `ChatWebController.roomView()` passes `isMember=false` to the template. The chat room template shows a join banner/modal prompting the user to join. Messages are visible but the message input is disabled until the user explicitly joins via the banner.

## Glossary

- **Bug_Condition (C)**: A non-member user clicks "Join" in the catalog — the system navigates to the chat view without creating a `RoomMember` entry
- **Property (P)**: When a non-member clicks "Join" in the catalog, a `RoomMember` with `MEMBER` role is created before the redirect; when a non-member navigates directly to a public room, they see a join prompt and cannot send messages until they join
- **Preservation**: Existing members accessing rooms, API-based joins, banned-user rejections, catalog browsing, private/direct room access, and message sending for members must remain unchanged
- **`RoomWebController`**: The `@Controller` at `/rooms` that currently handles catalog browsing and room creation form
- **`ChatWebController.roomView()`**: The `@GetMapping("/chat/rooms/{id}")` handler that renders the chat room page
- **`RoomMemberService.joinPublicRoom()`**: The service method that creates a `RoomMember` entry for a user in a public room, with ban and duplicate-membership checks
- **`RoomMemberService.isMember()`**: Returns `true` if a `RoomMember` record exists for the given room and user

## Bug Details

### Bug Condition

The bug manifests when a non-member user clicks "Join" on a public room in the catalog. The catalog template renders the "Join" button as `<a th:href="@{/chat/rooms/{id}(id=${entry.id()})}" class="btn btn-outline-primary btn-sm">Join</a>`, which navigates directly to the chat view. No join logic is invoked, so no `RoomMember` entry is created. The user sees messages but `MessageService.sendMessage()` rejects their messages with a `ForbiddenException` because `isMember()` returns `false`.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type CatalogJoinAction { user: User, room: Room, action: String }
  OUTPUT: boolean

  RETURN input.room.visibility = PUBLIC
         AND NOT roomMemberRepository.existsByRoomAndUser(input.room, input.user)
         AND input.action = "catalog_join_click"
END FUNCTION
```

### Examples

- User Alice (not a member) clicks "Join" on room "General" in the catalog → navigated to `/chat/rooms/{general-id}` → sees messages but cannot send (no `RoomMember` created). **Expected**: The "Join" click POSTs to `/rooms/{general-id}/join`, creates membership, then redirects to the chat view where Alice can send messages.
- User Bob (not a member) directly types `/chat/rooms/{public-room-id}` in the browser → no membership, cannot send. **Expected**: Bob sees the chat view with messages visible but the message input disabled, plus a join banner/modal prompting him to join. After clicking "Join" in the banner, Bob becomes a member and can send messages.
- User Carol (already a member) clicks a room in the sidebar → navigates to `/chat/rooms/{id}` → works fine, can send messages. **Expected**: No change — Carol is already a member.
- User Dave (banned from room "Restricted") clicks "Join" in the catalog → should be rejected. **Expected**: `ForbiddenException` from `joinPublicRoom()` ban check, rendered as error page.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Existing room members navigating to `/chat/rooms/{id}` must continue to see the chat view and send messages without any re-join attempt
- The REST API endpoint `POST /api/rooms/{id}/join` must continue to work identically
- Banned users attempting to join a public room must continue to be rejected with a forbidden error
- The room catalog page (`/rooms/catalog`) search, pagination, and display layout must remain unchanged (only the "Join" button behavior changes)
- Private and direct room access must remain unchanged — no join prompt for non-public rooms
- WebSocket message operations for existing members must remain unchanged

**Scope:**
All inputs where the user is already a member, or the room is not public, or the request comes through the REST API, should be completely unaffected by this fix. This includes:
- Existing members navigating to any room
- API-based join requests (`POST /api/rooms/{id}/join`)
- Private room and direct chat access
- Catalog browsing and search (layout/pagination)
- All WebSocket message operations for members

## Hypothesized Root Cause

Based on the code analysis, the root cause has two parts:

1. **Catalog "Join" button is a plain link**: In `catalog.html` (line ~48), the "Join" button is `<a th:href="@{/chat/rooms/{id}(id=${entry.id()})}" class="btn btn-outline-primary btn-sm">Join</a>`. This navigates directly to the chat view without invoking any join logic. There is no web-layer join endpoint — only the REST API has `POST /api/rooms/{id}/join` in `RoomApiController`.

2. **`ChatWebController.roomView()` does not check membership**: The method loads the room, user, members, and messages, then renders the template. It never checks whether the current user is a member, so the template has no way to conditionally show/hide the message input or display a join prompt.

3. **`MessageService.sendMessage()` membership gate is correct**: The `sendMessage()` method correctly checks `roomMemberService.isMember(room, sender)` and throws `ForbiddenException` if the user is not a member. This is working as designed — the bug is that membership is never established via the catalog flow, and the UI doesn't communicate the non-member state.

## Correctness Properties

Property 1: Bug Condition — Catalog Join creates membership before redirect

_For any_ non-member user who clicks "Join" on a public room in the catalog, the new `POST /rooms/{id}/join` endpoint in `RoomWebController` SHALL create a `RoomMember` entry with `MEMBER` role for that user and then redirect to `/chat/rooms/{id}`, where the user can see messages AND send messages.

**Validates: Requirements 2.1, 2.2, 2.3**

Property 2: Non-member direct navigation — Join prompt shown, sending disabled

_For any_ non-member user who navigates directly to `/chat/rooms/{id}` for a public room (without going through the catalog join flow), the `ChatWebController.roomView()` SHALL pass `isMember=false` to the template, the template SHALL display a join banner/modal, messages SHALL be visible, and the message input SHALL be disabled until the user explicitly joins.

**Validates: Requirements 2.1, 2.2**

Property 3: Preservation — Existing member room access unchanged

_For any_ user who is already a member of a room (any visibility), navigating to `/chat/rooms/{id}` SHALL produce the same result as the original code: the chat page renders with messages, members, and an active message input. No join prompt is shown.

**Validates: Requirements 3.1, 3.5**

Property 4: Preservation — Banned user rejection preserved

_For any_ user who is banned from a public room, attempting to join via `POST /rooms/{id}/join` SHALL result in a `ForbiddenException` (403 error page), preserving the existing ban enforcement from `RoomMemberService.joinPublicRoom()`.

**Validates: Requirements 3.3**

Property 5: Preservation — Non-public rooms unaffected

_For any_ room with visibility `PRIVATE` or `DIRECT`, the fixed `ChatWebController.roomView()` SHALL NOT show a join prompt or attempt any join logic, preserving the original behavior where only explicit invitations or direct chat creation grant access.

**Validates: Requirements 3.1, 3.5**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `src/main/java/com/bovae/yac/controller/web/RoomWebController.java`

**New Endpoint**: `POST /rooms/{id}/join`

**Specific Changes**:
1. **Inject `RoomMemberService` and `UserRepository`** into `RoomWebController` (currently only has `RoomService`).
2. **Add `joinRoom()` method**: A `@PostMapping("/{id}/join")` handler that resolves the room and user, calls `roomMemberService.joinPublicRoom(room, user)`, and returns `"redirect:/chat/rooms/" + id`. This handles ban checks (throws `ForbiddenException`) and duplicate membership (throws `ConflictException`) internally via the existing service logic.
3. **Accept `Principal` parameter** to resolve the authenticated user.

---

**File**: `src/main/resources/templates/rooms/catalog.html`

**Change**: Replace the "Join" `<a>` link with a form POST

**Specific Changes**:
1. **Replace** `<a th:href="@{/chat/rooms/{id}(id=${entry.id()})}" class="btn btn-outline-primary btn-sm">Join</a>` with a `<form>` that POSTs to `/rooms/{entry.id()}/join`. The form contains a CSRF token (auto-included by Thymeleaf) and a submit button styled as the existing "Join" button.

---

**File**: `src/main/java/com/bovae/yac/controller/web/ChatWebController.java`

**Method**: `roomView()`

**Specific Changes**:
1. **Add membership check**: After resolving the room and user, call `roomMemberService.isMember(room, user)` and store the result.
2. **Add model attribute**: Pass `isMember` (boolean) to the template via `model.addAttribute("isMember", isMember)`.
3. **No auto-join logic**: The controller does NOT call `joinPublicRoom()`. Non-members simply see the room in read-only mode with a join prompt.

---

**File**: `src/main/resources/templates/chat/room.html`

**Changes**: Conditional UI based on `isMember`

**Specific Changes**:
1. **Join banner**: When `isMember` is `false` and the room is `PUBLIC`, display a banner at the top of the message area (or a modal) with text like "You're not a member of this room" and a "Join Room" button. The button submits a form POST to `/rooms/{room.id}/join`.
2. **Disable message input**: When `isMember` is `false`, hide or disable the message input fragment (textarea, send button, file upload). Use `th:if="${isMember}"` on the message-input fragment include, or wrap it in a conditional block. Show a disabled placeholder like "Join this room to send messages" instead.
3. **Messages remain visible**: The message list is always rendered regardless of membership status, so non-members can read the conversation.

---

**File**: `src/main/resources/templates/fragments/message-input.html` (optional)

**No changes required** to the fragment itself. The conditional rendering is handled in `room.html` by wrapping the fragment include with `th:if="${isMember}"`.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm that the catalog "Join" button does not create membership and that `ChatWebController.roomView()` does not pass membership status to the template.

**Test Plan**: Write MockMvc integration tests that authenticate as a non-member user, simulate the catalog join flow, and assert that no `RoomMember` entry exists afterward. Run these tests on the UNFIXED code to observe failures and confirm the root cause.

**Test Cases**:
1. **Catalog join click (GET)**: Authenticate as a non-member, GET `/chat/rooms/{publicRoomId}` (simulating the current `<a>` link behavior), assert `roomMemberRepository.existsByRoomAndUser()` returns `false` (will confirm bug on unfixed code — no membership created)
2. **Non-member message send attempt**: After visiting the room view as a non-member, attempt to send a message via WebSocket, assert `ForbiddenException` is thrown (will confirm bug on unfixed code)
3. **No isMember model attribute**: GET `/chat/rooms/{publicRoomId}` as a non-member, assert the model does NOT contain an `isMember` attribute (will confirm bug on unfixed code — no membership check)

**Expected Counterexamples**:
- `roomMemberRepository.existsByRoomAndUser(room, user)` returns `false` after clicking "Join" in catalog
- The model does not contain `isMember` attribute, so the template cannot conditionally render the join prompt
- Root cause confirmed: catalog "Join" is a plain link, and `roomView()` never checks membership

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed code produces the expected behavior.

**Pseudocode:**
```
// Catalog join flow
FOR ALL input WHERE isBugCondition(input) DO
  result := POST /rooms/{input.room.id}/join
  ASSERT roomMemberRepository.existsByRoomAndUser(input.room, input.user) = true
  ASSERT findMember(input.room, input.user).role = MEMBER
  ASSERT result.statusCode = 302
  ASSERT result.redirectUrl = "/chat/rooms/" + input.room.id
END FOR

// Direct navigation flow (non-member)
FOR ALL input WHERE isNonMemberDirectNavigation(input) DO
  result := GET /chat/rooms/{input.room.id}
  ASSERT result.model.isMember = false
  ASSERT result.statusCode = 200
  ASSERT result.viewName = "chat/room"
  // Template renders join banner, disables message input
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalFunction(input) = fixedFunction(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many combinations of room visibility, membership status, and user roles
- It catches edge cases like concurrent join attempts or rooms with many members
- It provides strong guarantees that existing member access is unchanged

**Test Plan**: Observe behavior on UNFIXED code first for existing members accessing rooms, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Existing member access preservation**: Verify that existing members (OWNER, ADMIN, MEMBER) can access rooms with `isMember=true` in the model, message input active, no join prompt shown
2. **Private room access preservation**: Verify that non-members accessing private rooms do NOT see a join prompt (non-public rooms are unaffected)
3. **API join preservation**: Verify that `POST /api/rooms/{id}/join` continues to work identically
4. **Catalog browsing preservation**: Verify that `/rooms/catalog` search and pagination are unchanged (only the "Join" button markup changes)

### Unit Tests

- Test `RoomWebController.joinRoom()` with mocked services: non-member + public room → `joinPublicRoom()` called, redirect to `/chat/rooms/{id}`
- Test `RoomWebController.joinRoom()` with mocked services: banned user + public room → `ForbiddenException` propagated
- Test `RoomWebController.joinRoom()` with mocked services: already a member → `ConflictException` propagated
- Test `ChatWebController.roomView()` with mocked services: non-member + public room → model contains `isMember=false`
- Test `ChatWebController.roomView()` with mocked services: existing member → model contains `isMember=true`
- Test `ChatWebController.roomView()` with mocked services: non-member + private room → model contains `isMember=false`, no join logic attempted

### Property-Based Tests

- Generate random combinations of (room visibility, membership status, ban status) and verify that `POST /rooms/{id}/join` only succeeds for non-member + public + non-banned inputs
- Generate random existing members with various roles (OWNER, ADMIN, MEMBER) and verify `roomView()` always sets `isMember=true` and the model is consistent
- Generate random room configurations and verify that the join prompt is only shown for PUBLIC rooms where the user is not a member

### Integration Tests

- Full MockMvc test: non-member POSTs to `/rooms/{publicRoomId}/join` → verify `RoomMember` created with `MEMBER` role, response is 302 redirect to `/chat/rooms/{id}`
- Full MockMvc test: after joining via POST, GET `/chat/rooms/{id}` → verify `isMember=true` in model, message input active
- Full MockMvc test: non-member GETs `/chat/rooms/{publicRoomId}` directly → verify `isMember=false` in model, page renders with 200
- Full MockMvc test: banned user POSTs to `/rooms/{publicRoomId}/join` → verify 403 error
- Full MockMvc test: existing member GETs `/chat/rooms/{id}` → verify `isMember=true`, no duplicate membership, page renders normally
- Full MockMvc test: non-member GETs `/chat/rooms/{privateRoomId}` → verify no join prompt for non-public rooms
