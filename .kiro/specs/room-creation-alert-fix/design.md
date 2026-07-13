# Room Creation Alert Fix — Bugfix Design

## Overview

Two related bugs affect the room creation flow and admin actions in YAC. The primary bug is that `POST /api/rooms` returns `ResponseEntity<Void>` with HTTP 201 and no body, but the JavaScript in `create.html` calls `resp.json()` on the response — which throws on an empty body, falling into the `.catch()` handler and showing a false "Failed to create room" `alert()`. The room is actually created successfully, but the user sees an error and is never redirected. The secondary bug is that all error/confirmation feedback in `app.js` and `create.html` uses native `alert()`/`confirm()` dialogs instead of Bootstrap modals, creating an inconsistent UX.

The fix has two parts: (1) make the API return a JSON body with the created room's details so the JS can parse the `id` and redirect, and (2) replace all `alert()`/`confirm()` calls with Bootstrap modals matching the existing patterns in `admin-modals.html`.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the primary bug — a successful room creation where the API returns HTTP 201 with an empty body, causing `resp.json()` to throw
- **Property (P)**: The desired behavior — the API returns a JSON body with room details (`id`, `name`, `description`, `visibility`) on 201, and all user-facing feedback uses Bootstrap modals instead of native dialogs
- **Preservation**: Existing behaviors that must remain unchanged — error responses from the API, success-path page reloads/redirects for admin actions, inline feedback in the invite modal, and the existing `deleteRoomModal`
- **RoomApiController.createRoom**: The endpoint handler in `RoomApiController.java` that currently returns `ResponseEntity<Void>` with 201
- **RoomService.createRoom**: The service method that creates a room and already returns the saved `Room` entity (the controller just ignores the return value)
- **CreateRoomResponse**: A new response DTO (Java record) to carry room details back to the client
- **ErrorResponse**: The existing error DTO (`timestamp`, `status`, `message`, `path`) returned by `GlobalApiExceptionHandler`

## Bug Details

### Bug Condition

The primary bug manifests when a user submits the room creation form and the API successfully creates the room. The `RoomApiController.createRoom` method returns `ResponseEntity.status(HttpStatus.CREATED).build()` — an HTTP 201 with no body. The JavaScript then calls `resp.json()` which throws a `SyntaxError` on the empty body, causing the `.catch()` handler to execute `alert('Failed to create room')`.

The secondary bug manifests whenever any error or confirmation feedback is needed in `app.js` or `create.html` — the code uses native `alert()` and `confirm()` instead of Bootstrap modals.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type { request: HTTPRequest, context: "room-creation" | "admin-action" }
  OUTPUT: boolean

  IF input.context == "room-creation" THEN
    RETURN request is POST /api/rooms
           AND server returns HTTP 201
           AND response body is empty
           AND client calls resp.json() on empty body
  END IF

  IF input.context == "admin-action" THEN
    RETURN (action IN ["kick", "ban", "promote", "demote", "delete-room", "create-room-error"])
           AND feedback is displayed via native alert() or confirm()
  END IF

  RETURN FALSE
END FUNCTION
```

### Examples

- **Room creation success (primary bug)**: User fills in name="General", description="Main chat", visibility=PUBLIC and submits. API creates room with id `a1b2c3...`, returns 201 with empty body. JS calls `resp.json()` → throws `SyntaxError` → `.catch()` fires → `alert('Failed to create room')`. User sees error despite room being created. Expected: API returns `{"id":"a1b2c3...","name":"General","description":"Main chat","visibility":"PUBLIC"}`, JS parses id, redirects to `/chat/rooms/a1b2c3...`.
- **Room creation error (secondary bug)**: User submits a duplicate room name. API returns 409 with `ErrorResponse` JSON. JS parses it and calls `alert(data.message)` — a native dialog. Expected: error message shown in a Bootstrap modal.
- **Kick member (secondary bug)**: Admin clicks kick on a member. JS calls `confirm('Remove user from this room?')` — a native dialog. Expected: Bootstrap confirmation modal with Cancel/Confirm buttons.
- **Ban member error (secondary bug)**: Admin bans a member but API returns an error. JS calls `alert(err.message)` — a native dialog. Expected: error shown in Bootstrap modal.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- API error responses (400, 403, 404, 409, 500) must continue to use the existing `ErrorResponse` DTO shape (`timestamp`, `status`, `message`, `path`)
- Successful kick/ban must continue to reload the page via `window.location.reload()`
- Successful promote/demote must continue to reload the page via `window.location.reload()`
- Successful room deletion must continue to redirect to `/chat`
- Successful invitation must continue to show inline feedback in the invite modal (`#invite-feedback`)
- The existing `deleteRoomModal` in `admin-modals.html` must continue to be used for delete room confirmation
- `RoomService.createRoom` must continue to return the saved `Room` entity (it already does)
- CSRF protection on all API calls must remain intact

**Scope:**
All inputs that do NOT involve (a) the room creation success response shape or (b) native `alert()`/`confirm()` calls should be completely unaffected by this fix. This includes:
- Message sending, editing, deletion
- File uploads and downloads
- WebSocket/STOMP real-time messaging
- Presence heartbeats
- Friend request lifecycle
- Room catalog search and pagination
- User profile and session management

## Hypothesized Root Cause

Based on the bug analysis, the root causes are:

1. **Empty response body on room creation (primary bug)**: `RoomApiController.createRoom` calls `ResponseEntity.status(HttpStatus.CREATED).build()` which produces a 201 with no body. The `RoomService.createRoom` method already returns the saved `Room` entity, but the controller discards it. The JS in `create.html` unconditionally calls `resp.json()` on a successful response, which throws `SyntaxError` on an empty body.

2. **No response DTO for room creation**: There is no `CreateRoomResponse` DTO to serialize the created room's details. The existing `RoomCatalogEntry` includes `memberCount` which is not relevant here, and lacks `visibility`. A dedicated response record is needed.

3. **Native dialog usage in create.html**: The inline `<script>` in `create.html` uses `alert()` for both the error path (`alert(data.message || 'Failed to create room')`) and the catch-all path (`alert('Failed to create room')`). No Bootstrap modal markup exists on this page for error feedback.

4. **Native dialog usage in app.js**: The `kickMember` and `banMember` functions use `confirm()` for confirmation prompts. The `kickMember`, `banMember`, `promoteToAdmin`, `demoteToMember`, and `deleteRoom` functions all use `alert()` for error feedback. These should use Bootstrap modals consistent with the patterns in `admin-modals.html`.

## Correctness Properties

Property 1: Bug Condition - Room Creation Returns JSON Body

_For any_ valid `CreateRoomRequest` submitted to `POST /api/rooms` that results in a successful room creation, the API SHALL return HTTP 201 with a JSON body containing the created room's `id` (UUID), `name` (String), `description` (String), and `visibility` (enum), enabling the client to parse the response and redirect to `/chat/rooms/{id}`.

**Validates: Requirements 2.1**

Property 2: Preservation - Error Response Shape Unchanged

_For any_ request to `POST /api/rooms` that triggers a validation error, conflict, or other exception, the API SHALL continue to return the same `ErrorResponse` DTO shape (`timestamp`, `status`, `message`, `path`) with the same HTTP status codes as before the fix, preserving all existing error handling behavior.

**Validates: Requirements 3.1**

Property 3: Preservation - Admin Action Success Paths Unchanged

_For any_ successful admin action (kick, ban, promote, demote, delete room, send invitation), the JavaScript SHALL continue to perform the same post-success behavior (page reload or redirect or inline feedback) as before the fix, preserving all existing success-path functionality.

**Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.6**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `src/main/java/com/bovae/yac/model/dto/CreateRoomResponse.java` (NEW)

**Specific Changes**:
1. **Create response DTO**: Add a new `CreateRoomResponse` Java record with fields `id` (UUID), `name` (String), `description` (String), `visibility` (RoomVisibility). Use `@JsonNaming(SnakeCaseStrategy.class)` to match the project's snake_case convention.

**File**: `src/main/java/com/bovae/yac/controller/api/RoomApiController.java`

**Function**: `createRoom`

**Specific Changes**:
2. **Return room details**: Change the return type from `ResponseEntity<Void>` to `ResponseEntity<CreateRoomResponse>`. Capture the `Room` entity returned by `roomService.createRoom(...)`, map it to a `CreateRoomResponse`, and return it with `ResponseEntity.status(HttpStatus.CREATED).body(response)`.

**File**: `src/main/resources/templates/rooms/create.html`

**Specific Changes**:
3. **Add error modal markup**: Add a reusable Bootstrap error modal (`#errorModal`) to the page, following the same structure as modals in `admin-modals.html` (modal-dialog, modal-content, modal-header with title, modal-body with dynamic message, modal-footer with close button).
4. **Replace alert() with modal**: In the inline `<script>`, replace both `alert()` calls with logic that sets the modal body text and shows the modal via `new bootstrap.Modal(document.getElementById('errorModal')).show()`.

**File**: `src/main/resources/static/js/app.js`

**Specific Changes**:
5. **Add utility function for error modal**: Create a `showErrorModal(message)` function that finds or creates a Bootstrap error modal element, sets its body text, and shows it. This avoids duplicating modal logic across every error handler.
6. **Add utility function for confirmation modal**: Create a `showConfirmModal(message, onConfirm)` function that shows a Bootstrap confirmation modal with Cancel and Confirm buttons, calling the `onConfirm` callback when the user confirms.
7. **Replace confirm() in kickMember**: Replace `confirm('Remove ' + username + ' from this room?')` with `showConfirmModal(message, callback)` where the callback performs the fetch.
8. **Replace confirm() in banMember**: Same pattern as kickMember — replace `confirm()` with `showConfirmModal`.
9. **Replace alert() in error handlers**: In `kickMember`, `banMember`, `promoteToAdmin`, `demoteToMember`, and `deleteRoom`, replace `alert(err.message || '...')` with `showErrorModal(message)`.

**File**: `src/main/resources/templates/chat/room.html` (or a new shared fragment)

**Specific Changes**:
10. **Add confirmation and error modal markup**: Add the confirmation modal (`#confirmModal`) and error modal (`#errorModal`) markup to the chat room page so `app.js` can reference them. These could be added as a new Thymeleaf fragment or directly in `room.html` alongside the existing `admin-modals` include.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write tests that call `POST /api/rooms` with valid data and inspect the response body. Run these tests on the UNFIXED code to observe that the response body is empty, confirming the root cause.

**Test Cases**:
1. **Empty Body Test**: Call `POST /api/rooms` with valid `CreateRoomRequest`, assert response status is 201, assert response body is empty/null (will confirm bug on unfixed code)
2. **JSON Parse Failure Simulation**: Verify that calling `.json()` on an empty 201 response throws (confirms the JS-side failure mechanism)
3. **Alert Usage Audit**: Search `app.js` for `alert(` and `confirm(` calls to confirm all locations that need replacement (will confirm secondary bug)

**Expected Counterexamples**:
- `POST /api/rooms` returns 201 with empty body — `resp.json()` throws `SyntaxError`
- `kickMember` and `banMember` call `confirm()` — native dialog appears
- Error handlers in 5 functions call `alert()` — native dialog appears

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  IF input.context == "room-creation" THEN
    response := POST /api/rooms with valid CreateRoomRequest
    ASSERT response.status == 201
    ASSERT response.body != null
    ASSERT response.body.id IS UUID
    ASSERT response.body.name == input.request.name
    ASSERT response.body.description == input.request.description
    ASSERT response.body.visibility == input.request.visibility
  END IF
  IF input.context == "admin-action" THEN
    ASSERT no calls to native alert() or confirm()
    ASSERT Bootstrap modal is shown with appropriate message
  END IF
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
- It generates many valid `CreateRoomRequest` inputs and verifies the error paths still return the same `ErrorResponse` shape
- It catches edge cases in the response DTO mapping (null description, different visibility values)
- It provides strong guarantees that non-buggy paths are unchanged

**Test Plan**: Observe behavior on UNFIXED code first for error responses and admin action success paths, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Error Response Preservation**: Verify that `POST /api/rooms` with a duplicate name still returns 409 with `ErrorResponse` shape after the fix
2. **Validation Error Preservation**: Verify that `POST /api/rooms` with blank name still returns 400 with validation error message
3. **Admin Success Path Preservation**: Verify that successful kick/ban/promote/demote still triggers page reload
4. **Delete Room Success Preservation**: Verify that successful room deletion still redirects to `/chat`
5. **Invite Feedback Preservation**: Verify that successful invitation still shows inline feedback (not a modal)

### Unit Tests

- Test `CreateRoomResponse` record serialization produces correct snake_case JSON with all fields
- Test `RoomApiController.createRoom` returns 201 with `CreateRoomResponse` body containing correct room details
- Test `RoomApiController.createRoom` still returns 409 via exception handler for duplicate names
- Test `showErrorModal` creates/shows a Bootstrap modal with the given message
- Test `showConfirmModal` shows a modal and invokes callback on confirm

### Property-Based Tests

- Generate random valid `CreateRoomRequest` inputs (varying name lengths, null/non-null descriptions, PUBLIC/PRIVATE visibility) and verify the API returns 201 with a `CreateRoomResponse` whose fields match the request
- Generate random room names including duplicates and verify error responses maintain the `ErrorResponse` shape
- Generate random sequences of admin actions and verify success paths still perform the correct post-action behavior (reload/redirect)

### Integration Tests

- Test full room creation flow: submit form → API returns 201 with JSON → JS parses id → redirect to `/chat/rooms/{id}`
- Test room creation error flow: submit duplicate name → API returns 409 → JS shows Bootstrap error modal (not native alert)
- Test kick member flow: click kick → Bootstrap confirmation modal appears → confirm → API call → page reloads
- Test ban member flow: click ban → Bootstrap confirmation modal appears → confirm → API call → page reloads
- Test admin action error flow: API returns error → Bootstrap error modal appears (not native alert)
