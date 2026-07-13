# Multi-Bug Fixes Design

## Overview

This document addresses seven bugs in the YAC application spanning REST API validation, WebSocket transaction management, frontend presence display, password reset persistence, invitation visibility, room display naming, and Thymeleaf template rendering. Each bug is analyzed with a formal bug condition, root cause hypothesis, and targeted fix.

## Glossary

- **Bug_Condition (C)**: The specific input/state combination that triggers each bug
- **Property (P)**: The desired correct behavior when the bug condition is met
- **Preservation**: Existing behaviors that must remain unchanged after each fix
- **ChatMessageRequest**: The DTO record used for both sending and editing messages (has `@NotNull UUID roomId`)
- **ChatMessageHandler**: The WebSocket STOMP controller that handles `/app/chat.send`
- **subscribeToAllVisibleUsers()**: Function in `presence.js` that subscribes to presence topics for all visible dots
- **fetchInitialPresence()**: Function in `presence.js` that fetches current status from `/api/presence` for given user IDs

## Bug Details

### Bug 1: Message Edit Fails with "roomId: must not be null"

#### Bug Condition

The edit endpoint `PUT /api/rooms/{roomId}/messages/{id}` uses `@Valid @RequestBody ChatMessageRequest` for validation. `ChatMessageRequest` is a Java record defined as:

```java
public record ChatMessageRequest(
    @NotNull UUID roomId,
    @NotBlank @MaxByteSize(3072) String content,
    UUID replyToId
) {}
```

The `@NotNull` on `roomId` exists because the WebSocket send path (`/app/chat.send`) needs `roomId` in the payload to know which room to route the message to. However, the REST edit endpoint already has `roomId` in the URL path — the frontend correctly sends only `{"content": "new text"}` in the body. Since `ChatMessageRequest` is reused for both paths, the `@NotNull` validation fires on the missing `roomId` field during edit.

We cannot simply remove `@NotNull` from `ChatMessageRequest` because that would break the WebSocket send path which relies on `roomId` being present in the payload. The correct fix is a separate DTO for the edit endpoint that only requires `content`.

**Formal Specification:**
```
FUNCTION isBugCondition_Bug1(input)
  INPUT: input of type HTTP PUT request to /api/rooms/{roomId}/messages/{id}
  OUTPUT: boolean

  RETURN input.requestBody.roomId IS NULL
         AND input.pathVariable.roomId IS NOT NULL
         AND input.requestBody.content IS NOT NULL
END FUNCTION
```

#### Examples

- User edits message with body `{"content": "updated text"}` → 400 "roomId: must not be null"
- User edits message with body `{"content": "updated text", "roomId": "..."}` → 200 OK (workaround, but frontend shouldn't need to do this)

### Bug 2: LazyInitializationException on WebSocket Message Send

#### Bug Condition

`ChatMessageHandler.sendMessage()` is not `@Transactional`. After `messageService.sendMessage()` completes (closing its transaction), the handler calls `roomMemberRepository.findByRoom(room)` which returns `RoomMember` entities with lazy-loaded `user` associations. Accessing `member.getUser().getId()` triggers lazy initialization outside a session.

**Formal Specification:**
```
FUNCTION isBugCondition_Bug2(input)
  INPUT: input of type WebSocket STOMP message to /app/chat.send
  OUTPUT: boolean

  RETURN input.destination == "/app/chat.send"
         AND room.members.size() > 1
         AND handler.isNotTransactional()
END FUNCTION
```

#### Examples

- User sends message in room with 2+ members → LazyInitializationException when iterating members
- User sends message in single-member room (Saved Messages) → may succeed if no iteration needed

### Bug 3: Online Status Always Shows Gray

#### Bug Condition

The `subscribeToAllVisibleUsers()` function in `presence.js` subscribes to `/topic/presence.{userId}` for each visible presence dot in the DOM, but does NOT call `fetchInitialPresence()` for those users. The `fetchInitialPresence()` function is only called from `populateContacts()` in `sidebar.js` for the contact list.

Server-rendered member list dots (in `member-list.html`) start with CSS class `bg-secondary` (gray) and never get their initial status fetched. The presence broadcast for already-ONLINE users happened BEFORE the subscription was set up (during page load), so the subscriber missed it. Dots stay gray until the next status CHANGE (e.g., ONLINE → AFK), which may never come for a stable user.

**Formal Specification:**
```
FUNCTION isBugCondition_Bug3(input)
  INPUT: input of type page load with visible presence dots
  OUTPUT: boolean

  RETURN visiblePresenceDots.size() > 0
         AND dot.source == "server-rendered member list"
         AND fetchInitialPresence() NOT called for dot.userId
         AND presenceBroadcast for dot.userId occurred BEFORE subscription
END FUNCTION
```

#### Examples

- User opens a room with 5 members already online → all member list dots show gray (bg-secondary)
- Contact list dots show correct colors because `populateContacts()` calls `fetchInitialPresence()`
- If an already-online member goes AFK, their dot finally updates to yellow (because the subscription catches the CHANGE)
- New member comes online AFTER page load → dot correctly turns green (subscription catches it)

### Bug 4: Password Reset Doesn't Persist

#### Bug Condition

In `PasswordService.resetPassword()`, the `User` entity is obtained via `resetToken.getUser()` which returns a Hibernate lazy proxy. The proxy's `getId()` works without initialization (Hibernate optimization for `@Id` fields), but `setPasswordHash()` on the uninitialized proxy may not properly trigger dirty checking in all Hibernate versions, or the entity may be in an inconsistent managed state after the token save flushes.

**Formal Specification:**
```
FUNCTION isBugCondition_Bug4(input)
  INPUT: input of type PasswordResetConfirmRequest (token + newPassword)
  OUTPUT: boolean

  RETURN input.token IS VALID
         AND input.token IS NOT EXPIRED
         AND input.token IS NOT USED
         AND user.loadedViaLazyProxy == true
END FUNCTION
```

#### Examples

- User submits valid reset token + new password → response 200 OK, but old password still works
- Token is correctly marked as `used = true` in database
- User's `password_hash` column remains unchanged

### Bug 5: Room Invitation Invisible to Non-Friends

#### Bug Condition

The `populateRoomInvitations()` function in `sidebar.js` renders the invitation section with CSS class `collapse` (hidden by default). The section header has a toggle but no auto-expand. For users who are not friends with the inviter, there's no other visual cue (like a notification badge on the sidebar) that invitations exist. The invitations are technically rendered but effectively invisible.

**Formal Specification:**
```
FUNCTION isBugCondition_Bug5(input)
  INPUT: input of type User with pending room invitations
  OUTPUT: boolean

  RETURN user.pendingInvitations.size() > 0
         AND invitationSection.cssClass CONTAINS "collapse"
         AND invitationSection.cssClass NOT CONTAINS "show"
END FUNCTION
```

#### Examples

- Non-friend user receives room invitation → section rendered but collapsed/hidden
- User must manually click "Room Invitations" header to see pending invitations
- No badge or notification alerts user to check the collapsed section

### Bug 6: Saved Messages Room Shows Internal UUID Name

#### Bug Condition

The `ChatWebController.roomView()` passes the raw `Room` entity to the Thymeleaf template. The template displays `room.name` directly. For Saved Messages rooms, the name is `"saved-messages-{UUID}"` (set by `DirectChatService`). The sidebar handles this correctly (checks `other_display_name`/`other_username` being null and shows "Saved Messages 🔖"), but the chat header template does not.

**Formal Specification:**
```
FUNCTION isBugCondition_Bug6(input)
  INPUT: input of type Room displayed in chat header
  OUTPUT: boolean

  RETURN room.visibility == DIRECT
         AND room.name STARTS_WITH "saved-messages-"
         AND displayContext IN ["chat-header", "page-title"]
END FUNCTION
```

#### Examples

- User opens Saved Messages room → header shows "saved-messages-0d892201-8c42-44e0-b230-1c4fdd3c89bf"
- Page title shows "saved-messages-0d892201... - YAC"
- Sidebar correctly shows "Saved Messages 🔖"

### Bug 7: Sessions Page 500 Crash

#### Bug Condition

The Thymeleaf template `profile/sessions.html` uses `th:each="session : ${sessions}"`. The variable name `session` is reserved in Thymeleaf's web context (it refers to the HTTP session object). This causes `IllegalArgumentException` when Thymeleaf tries to set the iteration variable.

**Formal Specification:**
```
FUNCTION isBugCondition_Bug7(input)
  INPUT: input of type HTTP GET request to /profile/sessions
  OUTPUT: boolean

  RETURN template.iterationVariable == "session"
         AND "session" IN thymeleaf.reservedWords
END FUNCTION
```

#### Examples

- User navigates to `/profile/sessions` → HTTP 500
- Error: `IllegalArgumentException: Cannot set variable called 'session'`

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- WebSocket message sending via `/app/chat.send` with full `ChatMessageRequest` (roomId + content + replyToId) must continue to work
- REST message creation via `POST /api/rooms/{roomId}/messages` must continue to validate roomId
- Mouse/keyboard interactions unrelated to presence must continue working
- Password change flow (`POST /api/password/change`) must continue working
- Friend-to-friend room invitations must continue displaying correctly
- Non-DIRECT room names must continue displaying their actual stored name
- All other Thymeleaf templates must continue rendering correctly

**Scope:**
Each fix is scoped to its specific bug condition. Non-buggy inputs must produce identical behavior before and after the fix.

## Hypothesized Root Cause

1. **Bug 1 — Shared DTO**: `ChatMessageRequest` is shared between send (needs roomId) and edit (doesn't need roomId). The `@NotNull` on `roomId` triggers validation failure for edit requests.

2. **Bug 2 — Missing Transaction**: `ChatMessageHandler.sendMessage()` lacks `@Transactional`. After `messageService.sendMessage()` returns, the Hibernate session closes. The subsequent `roomMemberRepository.findByRoom(room)` opens a new session but returns entities with uninitialized lazy proxies for `user`. Alternatively, `findByRoom` uses the default query which doesn't JOIN FETCH user.

3. **Bug 3 — Missing Initial Presence Fetch for Member List**: `subscribeToAllVisibleUsers()` in `presence.js` subscribes to presence topics for all visible dots but never calls `fetchInitialPresence()` for those user IDs. The `fetchInitialPresence()` call only happens in `populateContacts()` (sidebar.js) for the contact list. Server-rendered member list dots start gray and miss the initial broadcast because it occurred before the subscription was established.

4. **Bug 4 — Lazy Proxy Dirty Checking**: `resetToken.getUser()` returns a Hibernate lazy proxy. Calling `setPasswordHash()` on the proxy may not reliably trigger dirty checking because the proxy might not be fully initialized before the setter is called, or the persistence context state after `passwordResetTokenRepository.save(resetToken)` flush interferes.

5. **Bug 5 — Collapsed Section**: The invitation section is rendered with `collapse` class (Bootstrap hidden state) and no `show` class. Users have no visual indicator that invitations exist unless they manually expand the section.

6. **Bug 6 — Raw Entity Name**: `ChatWebController` passes the raw `Room` entity to the template. The template unconditionally displays `room.name` without checking if it's a Saved Messages room that needs a friendly display name.

7. **Bug 7 — Reserved Variable**: Thymeleaf reserves `session` as a web context variable (maps to `HttpSession`). Using it as an iteration variable in `th:each` causes a conflict.

## Correctness Properties

Property 1: Bug Fixes — All Seven Bugs Resolved

_For any_ input where any of the seven bug conditions holds, the fixed code SHALL produce the correct expected behavior: edit accepts content-only body, WebSocket send completes without exception, presence dots update with correct color, password reset persists the new hash, invitations are visible without manual expansion, Saved Messages shows friendly name, and sessions page renders successfully.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12**

Property 2: Preservation — Existing Behavior Unchanged

_For any_ input where none of the bug conditions hold, the fixed code SHALL produce the same result as the original code, preserving all existing functionality for message sending, presence tracking, password changes, invitation display for friends, room name display for non-DIRECT rooms, and all other template rendering.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12**

## Fix Implementation

### Changes Required

#### Bug 1: Create a Separate Edit DTO

**File**: `src/main/java/com/bovae/yac/model/dto/EditMessageRequest.java` (new)

**Specific Changes**:
1. Create a new `EditMessageRequest` record with only `@NotBlank @MaxByteSize(3072) String content`
2. Update `MessageApiController.editMessage()` to use `EditMessageRequest` instead of `ChatMessageRequest`

#### Bug 2: Use JOIN FETCH for Room Members in Handler

**File**: `src/main/java/com/bovae/yac/ws/ChatMessageHandler.java`

**Specific Changes**:
1. Replace `roomMemberRepository.findByRoom(room)` with `roomMemberRepository.findByRoomWithUsers(room)` which already exists and uses `JOIN FETCH rm.user`
2. This ensures user entities are eagerly loaded within the repository query, avoiding lazy initialization issues

#### Bug 3: Fetch Initial Presence After Subscribing to Visible Users

**File**: `src/main/resources/static/js/presence.js`

**Function**: `subscribeToAllVisibleUsers()`

**Specific Changes**:
1. After subscribing to all visible presence dots, collect the user IDs into an array
2. Call `fetchInitialPresence(userIds)` with the collected IDs so that server-rendered member list dots get their current status immediately after subscription
3. This ensures dots that were rendered as `bg-secondary` (gray) get updated to the correct color on page load

#### Bug 4: Explicitly Reload User Entity

**File**: `src/main/java/com/bovae/yac/service/PasswordService.java`

**Specific Changes**:
1. Replace `User user = resetToken.getUser()` with `User user = userRepository.findById(resetToken.getUser().getId()).orElseThrow()`
2. This ensures the User entity is fully loaded and properly managed in the persistence context, guaranteeing dirty checking works correctly

#### Bug 5: Auto-Expand Invitation Section

**File**: `src/main/resources/static/js/sidebar.js`

**Specific Changes**:
1. Change the collapse div class from `collapse` to `collapse show` when invitations exist
2. This makes the invitation section visible by default when there are pending invitations

#### Bug 6: Display Friendly Name for Saved Messages

**File**: `src/main/java/com/bovae/yac/controller/web/ChatWebController.java`

**Specific Changes**:
1. After loading the room, check if it's a Saved Messages room (visibility == DIRECT and name starts with "saved-messages-")
2. Add a `displayName` model attribute with value "Saved Messages" for such rooms, or the actual `room.name` otherwise

**File**: `src/main/resources/templates/chat/room.html`

**Specific Changes**:
1. Change `<title>` tag from `th:text="${room.name} + ' - YAC'"` to use `displayName`
2. Change chat header `<h6>` from `th:text="${room.name}"` to `th:text="${displayName}"`
3. Change `window.YAC_ROOM` script block from `name: [[${room.name}]]` to `name: [[${displayName}]]`

**File**: `src/main/resources/templates/fragments/member-list.html`

**Specific Changes**:
1. Change room info panel `<p>` from `th:text="${room.name}"` to `th:text="${displayName != null ? displayName : room.name}"`
   (Uses fallback to `room.name` since this fragment may be used in contexts where `displayName` is not set)

#### Bug 7: Rename Thymeleaf Iteration Variable

**File**: `src/main/resources/templates/profile/sessions.html`

**Specific Changes**:
1. Change `th:each="session : ${sessions}"` to `th:each="sess : ${sessions}"`
2. Update all references within the loop from `session.` to `sess.` (e.g., `sess.creationTime()`, `sess.lastAccessedTime()`, `sess.sessionId()`)

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bugs on unfixed code, then verify the fixes work correctly and preserve existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Confirm each bug manifests as described before implementing fixes.

**Test Cases**:
1. **Bug 1**: Send PUT to `/api/rooms/{roomId}/messages/{id}` with body `{"content": "test"}` — expect 400 (will fail on unfixed code)
2. **Bug 2**: Send WebSocket message to room with 2+ members — expect LazyInitializationException in logs
3. **Bug 3**: Open a room with online members, check member list dots — expect all gray despite users being online
4. **Bug 4**: Complete password reset flow, attempt login with new password — expect failure
5. **Bug 5**: Create invitation for non-friend user, check sidebar — expect collapsed/hidden section
6. **Bug 6**: Open Saved Messages room — expect UUID in header
7. **Bug 7**: Navigate to `/profile/sessions` — expect 500 error

### Fix Checking

**Goal**: Verify that for all inputs where each bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR EACH bug IN [Bug1..Bug7] DO
  FOR ALL input WHERE isBugCondition_bug(input) DO
    result := fixedFunction(input)
    ASSERT expectedBehavior_bug(result)
  END FOR
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where no bug condition holds, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT anyBugCondition(input) DO
  ASSERT originalFunction(input) = fixedFunction(input)
END FOR
```

**Testing Approach**: Property-based testing for preservation checking where applicable (message sending, presence computation, password change flow).

### Unit Tests

- Test `EditMessageRequest` validation (content required, roomId absent)
- Test `ChatMessageHandler` with mocked `findByRoomWithUsers` returns properly loaded entities
- Test `subscribeToAllVisibleUsers` calls `fetchInitialPresence` with collected user IDs
- Test `PasswordService.resetPassword()` persists new password hash
- Test sessions template renders without error using non-reserved variable name

### Property-Based Tests

- Generate random message edit requests and verify only content is validated
- Generate random sets of visible presence dots and verify `fetchInitialPresence` is called with all user IDs

### Integration Tests

- Full message edit flow via REST API with content-only body
- WebSocket message send in multi-member room without exceptions
- Password reset flow end-to-end verifying new password works for login
- Sessions page renders successfully with active sessions
