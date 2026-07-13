# UX and Logic Bugs Round 2 — Bugfix Design

## Overview

This design addresses 14 bugs spanning backend lazy-loading issues, frontend/backend field name mismatches, missing UI behaviors, security gaps, and incorrect display logic in the YAC application. The bugs range from `LazyInitializationException` on message edit, to security vulnerabilities allowing unauthorized room access, to CSS/JS issues preventing core UI features from working.

## Glossary

- **Bug_Condition (C)**: The specific input or state that triggers each bug
- **Property (P)**: The desired correct behavior when the bug condition is met
- **Preservation**: Existing behaviors that must remain unchanged after fixes
- **LazyInitializationException**: Hibernate exception when accessing a lazy-loaded proxy outside a session
- **snake_case**: Jackson global naming strategy configured in `application.yml` (`property-naming-strategy: SNAKE_CASE`)
- **Watermark**: Monotonically increasing per-room message ordering value
- **STOMP**: Simple Text Oriented Messaging Protocol used for WebSocket communication

## Bug Details

### Bug Condition

The 14 bugs manifest under distinct conditions. Below is the formal specification for each:

**Bug 1: LazyInitializationException on message edit**

```
FUNCTION isBugCondition_1(input)
  INPUT: input of type {HTTP PUT request to /api/rooms/{roomId}/messages/{id}}
  OUTPUT: boolean

  RETURN input.method == "PUT"
         AND input.path matches "/api/rooms/{roomId}/messages/{id}"
         AND MessageApiController.toResponse(message) accesses message.getSender().getUsername()
         AND message.sender is a Hibernate lazy proxy (not initialized in current session)
END FUNCTION
```

**Bug 2: Reply-to "Original message deleted" not showing on live WebSocket delete**

```
FUNCTION isBugCondition_2(input)
  INPUT: input of type {WebSocket delete event OR REST DELETE response for a message}
  OUTPUT: boolean

  RETURN input.messageDeleted == true
         AND existsReplyQuoteInDOM(input.deletedMessageId)
         AND handleDeleteMessage() only calls messageItem.remove()
         AND does NOT update reply quotes referencing the deleted message
END FUNCTION
```

**Bug 3: Message action buttons hover area too wide**

```
FUNCTION isBugCondition_3(input)
  INPUT: input of type {CSS hover event on .message-item}
  OUTPUT: boolean

  RETURN input.hoverTarget is ".message-item" (full row)
         AND CSS rule ".message-item:hover .message-actions { opacity: 1 }" is active
         AND cursor is NOT over ".message-bubble"
END FUNCTION
```

**Bug 4: Public Room Catalog not clickable, Join button shown for members**

```
FUNCTION isBugCondition_4(input)
  INPUT: input of type {catalog page render for authenticated user}
  OUTPUT: boolean

  RETURN (input.roomEntry has no clickable link wrapping the room name/description)
         OR (input.user is already a member of input.room AND "Join" button is displayed)
END FUNCTION
```

**Bug 5: Sessions page missing browser details and IP**

```
FUNCTION isBugCondition_5(input)
  INPUT: input of type {GET /profile/sessions page render}
  OUTPUT: boolean

  RETURN AuthService.SessionInfo record does NOT contain userAgent or ipAddress fields
         AND sessions.html template does NOT render browser/IP info
END FUNCTION
```

**Bug 6: Password change validation error (camelCase vs snake_case)**

```
FUNCTION isBugCondition_6(input)
  INPUT: input of type {POST /api/password/change with JSON body}
  OUTPUT: boolean

  RETURN input.jsonBody contains keys "currentPassword" AND "newPassword"
         AND global Jackson strategy is SNAKE_CASE
         AND PasswordChangeRequest fields are "currentPassword" and "newPassword" (camelCase)
         AND Jackson cannot map camelCase JSON keys to camelCase record fields
              because SNAKE_CASE strategy expects "current_password" and "new_password"
END FUNCTION
```

**Bug 7: Display name not updating (same field name mismatch)**

```
FUNCTION isBugCondition_7(input)
  INPUT: input of type {PUT /api/users/me with JSON body}
  OUTPUT: boolean

  RETURN input.jsonBody contains key "displayName"
         AND global Jackson strategy is SNAKE_CASE
         AND UpdateProfileRequest field is "displayName" (camelCase)
         AND Jackson expects "display_name" in JSON but receives "displayName"
END FUNCTION
```

**Bug 8: DM room shows internal name instead of "Chat with <name>"**

```
FUNCTION isBugCondition_8(input)
  INPUT: input of type {GET /chat/rooms/{id} for a DIRECT room}
  OUTPUT: boolean

  RETURN input.room.visibility == DIRECT
         AND input.room.name starts with "dm-"
         AND ChatWebController sets displayName = room.getName()
         AND does NOT resolve the other user's display name
END FUNCTION
```

**Bug 9: Self-notification on message send**

```
FUNCTION isBugCondition_9(input)
  INPUT: input of type {message sent by user in a room}
  OUTPUT: boolean

  RETURN input.sender.id == input.user.id
         AND sender's lastReadWatermark < newMessage.watermark
         AND sidebar refresh triggers computeUnreadCount for sender
         AND unreadCount > 0 for sender
END FUNCTION
```

**Bug 10: Saved Messages accessible by non-members (security)**

```
FUNCTION isBugCondition_10(input)
  INPUT: input of type {GET /chat/rooms/{id} by non-member user}
  OUTPUT: boolean

  RETURN input.user is NOT a member of input.room
         AND input.room.visibility != PUBLIC
         AND ChatWebController.roomView() renders message content without access check
END FUNCTION
```

**Bug 11: Chat search doesn't work (CSS class mismatch)**

```
FUNCTION isBugCondition_11(input)
  INPUT: input of type {user types in sidebar search input}
  OUTPUT: boolean

  RETURN setupSearch() calls document.querySelector('.sidebar')
         AND actual sidebar container has class "chat-sidebar" (not "sidebar")
         AND querySelector returns null
         AND search input event listener is never attached
END FUNCTION
```

**Bug 12: Contacts shows self**

```
FUNCTION isBugCondition_12(input)
  INPUT: input of type {friendship list rendered in sidebar}
  OUTPUT: boolean

  RETURN getCurrentUserId() returns a string
         AND friendship API returns UUID strings
         AND comparison `friendship.requester_id === currentUserId` uses strict equality
         AND type coercion or format mismatch causes self not to be filtered out
END FUNCTION
```

**Bug 13: Room invitations not displayed (repository eager fetch issue)**

```
FUNCTION isBugCondition_13(input)
  INPUT: input of type {GET /api/rooms/invitations/pending}
  OUTPUT: boolean

  RETURN RoomInvitationRepository.findByInvitee(user) returns List<RoomInvitation>
         AND RoomInvitation.room and RoomInvitation.inviter are FetchType.LAZY
         AND pendingInvitations() accesses inv.getRoom().getId(), inv.getRoom().getName(),
             inv.getInviter().getUsername() outside a transaction
         AND LazyInitializationException is thrown (or empty/null data returned)
END FUNCTION
```

**Bug 14: Password reset fails (camelCase vs snake_case in reset form)**

```
FUNCTION isBugCondition_14(input)
  INPUT: input of type {POST /api/password/reset with JSON body}
  OUTPUT: boolean

  RETURN input.jsonBody contains key "newPassword" (camelCase)
         AND global Jackson strategy is SNAKE_CASE
         AND PasswordResetConfirmRequest field is "newPassword" (camelCase)
         AND Jackson expects "new_password" in JSON but receives "newPassword"
         AND field remains null → @NotBlank validation fails
END FUNCTION
```

### Examples

- Bug 1: User edits message → 500 error with `LazyInitializationException` in logs
- Bug 2: User A deletes a message that User B replied to → User B's reply quote disappears entirely instead of showing "Original message deleted"
- Bug 3: Cursor hovers on empty space to the left of a message bubble → action buttons appear
- Bug 4: User clicks room name in catalog → nothing happens; already-joined user sees "Join" button
- Bug 5: User views sessions page → only sees "Created" and "Last accessed" timestamps
- Bug 6: User fills password form, submits → sees "currentPassword: не должно быть пустым"
- Bug 7: User changes display name, submits → no error shown but name doesn't change
- Bug 8: User opens DM → header shows "dm-e5418c50-79307c3e"
- Bug 9: User sends message → their own sidebar shows unread badge on that room
- Bug 10: User navigates to `/chat/rooms/{savedMessagesId}` of another user → sees their messages
- Bug 11: User types in search box → room list doesn't filter
- Bug 12: User views contacts → sees themselves in the list
- Bug 13: User is invited to room → sidebar shows no invitation section
- Bug 14: User submits password reset form with valid token and new password → gets validation error "newPassword: must not be blank" because `newPassword` key is not recognized by SNAKE_CASE strategy

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- WebSocket message sending and broadcasting continues to work for all room members (3.1)
- Message deletion without reply references continues to simply remove the DOM element (3.2)
- Message action buttons continue to show with fade-in transition on bubble hover (3.3)
- Public Room Catalog continues to display room list with search and pagination (3.4)
- Session termination continues to invalidate sessions successfully (3.5)
- Password reset flow (forgot password) continues to work correctly (3.6)
- Profile page continues to display current display name and username (3.7)
- Saved Messages rooms continue to display "Saved Messages" as the room name (3.8)
- Recipients continue to receive unread notification badges (3.9)
- Members continue to access their rooms with full message history (3.10)
- Sidebar continues to populate all room lists and contacts correctly (3.11)
- Clicking a contact continues to initiate a direct chat (3.12)
- Accepting a room invitation continues to add user as member and redirect (3.13)

**Scope:**
All inputs that do NOT trigger the specific bug conditions should be completely unaffected. This includes normal message sending, room creation, friend management, and all other existing functionality.

## Hypothesized Root Cause

### Bug 1: LazyInitializationException on message edit

`MessageApiController.toResponse()` calls `message.getSender().getUsername()` but the `Message` entity returned from `messageService.editMessage()` has a lazy-loaded `sender` proxy. Since `open-in-view: false`, the Hibernate session is closed by the time the controller accesses the proxy.

### Bug 2: Reply-to not updating on delete

`handleDeleteMessage()` in `app.js` only calls `messageItem.remove()` on success. It does not scan the DOM for other `.reply-quote` elements that reference the deleted message ID and update them to show "Original message deleted".

### Bug 3: Hover area too wide

CSS rule `.message-item:hover .message-actions { opacity: 1 }` triggers on the entire `.message-item` row. The hover should be scoped to `.message-bubble:hover .message-actions` instead.

### Bug 4: Catalog not clickable / Join shown for members

In `catalog.html`, room entries are plain `<div>` elements with no `<a>` link wrapping. The template always renders the "Join" form without checking if the current user is already a member. The controller doesn't pass membership info to the template.

### Bug 5: Sessions missing browser/IP

`AuthService.SessionInfo` record only has `sessionId`, `creationTime`, `lastAccessedTime`. Spring Session stores attributes but the code doesn't extract User-Agent or IP from session attributes. The template only renders creation/last-accessed times.

### Bug 6: Password change field mismatch

The global Jackson `SNAKE_CASE` strategy means incoming JSON keys `current_password` and `new_password` map to Java fields `currentPassword` and `newPassword`. But `profile.js` sends `{ currentPassword: ..., newPassword: ... }` (camelCase keys). Jackson with SNAKE_CASE strategy expects snake_case keys in JSON, so the camelCase keys are not recognized and the `@NotBlank` fields remain null → validation fails.

### Bug 7: Display name field mismatch

Same root cause as Bug 6. `profile.js` sends `{ displayName: "..." }` but Jackson with SNAKE_CASE expects `{ "display_name": "..." }`. The `UpdateProfileRequest` record field `displayName` is never populated.

### Bug 8: DM room shows internal name

`ChatWebController.roomView()` only handles the `saved-messages-` prefix case. For regular DM rooms (name starts with `dm-`), it falls through to `model.addAttribute("displayName", room.getName())` which shows the internal name like "dm-e5418c50-79307c3e".

### Bug 9: Self-notification on message send

In `ChatMessageHandler.sendMessage()`, after broadcasting the message, it iterates room members and calls `notificationService.broadcastNotification()` for all members except the sender. However, the sender's `lastReadWatermark` is NOT updated after sending. When the sidebar refreshes (e.g., on page reload or periodic refresh), `computeUnreadCount` returns a positive value for the sender because their watermark is behind the new message.

### Bug 10: Saved Messages accessible by non-members

`ChatWebController.roomView()` checks `isMember` but only uses it to conditionally show the message input and mark-as-read. It does NOT deny access to non-members for non-PUBLIC rooms. The message history is always loaded and rendered regardless of membership.

### Bug 11: Search doesn't work (CSS class mismatch)

`setupSearch()` in `sidebar.js` calls `document.querySelector('.sidebar')` but the actual sidebar container in `room.html` uses class `chat-sidebar` (defined in the `<div class="chat-sidebar">` wrapper). The querySelector returns null, so the search input is never found and no event listener is attached.

### Bug 12: Contacts shows self

In `populateContacts()`, the comparison `friendship.requester_id === currentUserId` uses strict equality. `getCurrentUserId()` returns `window.YAC_USER.id` which is a string. The friendship API returns JSON with `requester_id` and `recipient_id` as strings. The issue is that the filtering logic determines which user is the "friend" by checking if `requester_id === currentUserId` — if it matches, the friend is the recipient. But if neither matches (due to format differences), both sides get added, including self.

### Bug 13: Room invitations not displayed

`RoomInvitationRepository.findByInvitee(user)` returns `List<RoomInvitation>` with lazy-loaded `room` and `inviter` associations. The `pendingInvitations()` method in `RoomApiController` accesses `inv.getRoom().getId()`, `inv.getRoom().getName()`, and `inv.getInviter().getUsername()` — but since `open-in-view: false` and there's no `@Transactional` annotation on the controller method, these lazy proxies throw `LazyInitializationException`.

### Bug 14: Password reset field mismatch

Same root cause as Bugs 6 and 7. The `reset-password.html` inline script sends `{ token: token, newPassword: newPassword }`. The `token` field maps correctly (single word, no case difference), but `newPassword` is not recognized by Jackson's SNAKE_CASE strategy (expects `new_password`). The `@NotBlank String newPassword` field in `PasswordResetConfirmRequest` stays null → validation fails.

## Correctness Properties

Property 1: Bug Condition - Message Edit Returns Valid Response

_For any_ PUT request to `/api/rooms/{roomId}/messages/{id}` with valid content, the fixed `MessageApiController.editMessage()` SHALL return a valid `ChatMessageResponse` with populated `senderId` and `senderUsername` fields without throwing `LazyInitializationException`.

**Validates: Requirements 2.1**

Property 2: Bug Condition - Reply Quotes Update on Delete

_For any_ message deletion where other messages in the DOM have reply quotes referencing the deleted message, the fixed delete handler SHALL update those reply quotes to display "Original message deleted" text.

**Validates: Requirements 2.2**

Property 3: Bug Condition - Action Buttons Only on Bubble Hover

_For any_ hover event on the message area, the fixed CSS SHALL only show message action buttons when the cursor is over the `.message-bubble` element, not the entire `.message-item` row.

**Validates: Requirements 2.3**

Property 4: Bug Condition - Catalog Entries Clickable and Membership-Aware

_For any_ authenticated user viewing the Public Room Catalog, the fixed template SHALL render room entries as clickable links navigating to the room page, and SHALL display "Joined" instead of "Join" for rooms where the user is already a member.

**Validates: Requirements 2.4**

Property 5: Bug Condition - Sessions Display Browser and IP

_For any_ session displayed on the Sessions page, the fixed implementation SHALL show browser details (parsed from User-Agent) and IP address when available.

**Validates: Requirements 2.5**

Property 6: Bug Condition - Password Change Succeeds

_For any_ password change submission from the profile page with valid current and new passwords, the fixed system SHALL successfully change the password without field-mapping validation errors.

**Validates: Requirements 2.6**

Property 7: Bug Condition - Display Name Update Persists

_For any_ display name update submission from the profile page, the fixed system SHALL persist the change and reflect the updated name.

**Validates: Requirements 2.7**

Property 8: Bug Condition - DM Room Shows Human-Readable Name

_For any_ DIRECT room that is not a Saved Messages room, the fixed `ChatWebController` SHALL display "Chat with <other_user_display_name_or_username>" in the header.

**Validates: Requirements 2.8**

Property 9: Bug Condition - No Self-Notification on Send

_For any_ message sent by a user, the fixed system SHALL update the sender's `lastReadWatermark` to the new message's watermark so that no unread badge appears for the sender.

**Validates: Requirements 2.9**

Property 10: Bug Condition - Non-Member Access Denied

_For any_ non-member user attempting to access a non-PUBLIC room, the fixed `ChatWebController` SHALL deny access and redirect or return an error.

**Validates: Requirements 2.10**

Property 11: Bug Condition - Sidebar Search Filters Correctly

_For any_ text typed in the sidebar search input, the fixed `setupSearch()` SHALL correctly locate the sidebar container and filter room/contact lists.

**Validates: Requirements 2.11**

Property 12: Bug Condition - Self Not Shown in Contacts

_For any_ friendship list rendered in the sidebar, the fixed `populateContacts()` SHALL exclude the current user from the displayed contacts.

**Validates: Requirements 2.12**

Property 13: Bug Condition - Room Invitations Displayed

_For any_ pending room invitation for the current user, the fixed system SHALL return complete invitation data (room name, inviter username) without lazy-loading exceptions.

**Validates: Requirements 2.13**

Property 14: Preservation - Existing Functionality Unchanged

_For any_ input where none of the 14 bug conditions hold, the fixed system SHALL produce the same behavior as the original system, preserving all existing functionality including message sending, room management, friend operations, and UI interactions.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13**

Property 15: Bug Condition - Password Reset Succeeds

_For any_ password reset submission with a valid token and new password, the fixed system SHALL successfully reset the password by ensuring the frontend sends `new_password` (snake_case) to match the backend DTO expectation.

**Validates: Requirements 2.14**

## Fix Implementation

### Changes Required

**Bug 1 Fix — LazyInitializationException on message edit**

**File**: `src/main/java/com/bovae/yac/repository/MessageRepository.java`

**Changes**:
1. Add a new repository method with JOIN FETCH for sender:
```java
@Query("SELECT m FROM Message m JOIN FETCH m.sender WHERE m.id = :id")
Optional<Message> findByIdWithSender(@Param("id") UUID id);
```
Only `m.sender` needs fetching — the room ID comes from the URL path variable, so there's no need to JOIN FETCH the room. This is a single-row lookup by primary key, so there's no N+1 concern.

**File**: `src/main/java/com/bovae/yac/service/MessageService.java`

**Changes**:
1. In `editMessage()`, replace `messageRepository.findById(messageId)` with `messageRepository.findByIdWithSender(messageId)`
2. This ensures the sender entity is initialized within the query, so `MessageApiController.toResponse()` can safely access `message.getSender().getUsername()` without triggering lazy initialization

---

**Bug 2 Fix — Reply quotes not updating on WebSocket delete**

**File**: `src/main/resources/static/js/app.js`

**Function**: `handleDeleteMessage`

**Changes**:
1. After `messageItem.remove()`, scan the DOM for all `.reply-quote` elements or `.message-item` elements whose `data-message-id` in reply references match the deleted message ID
2. For server-rendered messages, the reply quote doesn't store the referenced message ID directly — need to find reply quotes that reference the deleted message
3. Update the reply-quote content to "Original message deleted" and remove the sender name

**Specific Implementation**: After successful delete, query all elements with reply references to the deleted message ID and update their text content.

---

**Bug 3 Fix — Hover area too wide**

**File**: `src/main/resources/static/css/chat.css`

**Changes**:
1. Change `.message-item:hover .message-actions { opacity: 1 }` to `.message-bubble:hover .message-actions { opacity: 1 }`
2. Optionally remove or adjust the `.message-item:hover` background highlight rule to scope to bubble

---

**Bug 4 Fix — Catalog not clickable / Join shown for members**

**File**: `src/main/resources/templates/rooms/catalog.html`
**File**: `src/main/java/com/bovae/yac/controller/web/RoomWebController.java`
**File**: `src/main/java/com/bovae/yac/model/dto/RoomCatalogEntry.java`

**Changes**:
1. Wrap room entry content in an `<a>` tag linking to `/chat/rooms/{id}`
2. Pass current user's membership set to the template
3. Conditionally show "Joined" badge vs "Join" button based on membership
4. Add `isMember` field to `RoomCatalogEntry` or pass a separate set of joined room IDs to the model

---

**Bug 5 Fix — Sessions missing browser/IP**

**File**: `src/main/java/com/bovae/yac/service/AuthService.java`
**File**: `src/main/resources/templates/profile/sessions.html`

**Changes**:
1. Extend `SessionInfo` record to include `userAgent` and `ipAddress` fields
2. Extract these from session attributes (Spring Security stores them if configured)
3. Update the template to display browser info and IP
4. If session attributes don't contain this data, store User-Agent and IP on login via a session attribute listener or authentication success handler

---

**Bug 6 Fix — Password change field mismatch**

**File**: `src/main/resources/static/js/profile.js`

**Changes**:
1. Change the JSON body in `initChangePasswordForm()` from `{ currentPassword: ..., newPassword: ... }` to `{ current_password: ..., new_password: ... }` to match the global SNAKE_CASE Jackson strategy

---

**Bug 7 Fix — Display name field mismatch**

**File**: `src/main/resources/static/js/profile.js`

**Changes**:
1. Change the JSON body in `initDisplayNameForm()` from `{ displayName: ... }` to `{ display_name: ... }` to match the global SNAKE_CASE Jackson strategy

---

**Note on Bugs 6, 7, and 14 — Global snake_case audit**

All three bugs share the same root cause: frontend JavaScript sends camelCase JSON keys but the backend uses a global Jackson `SNAKE_CASE` naming strategy. A full audit of all `JSON.stringify` calls across the codebase confirms these are the ONLY three affected locations:

| File | Current (broken) | Expected (snake_case) |
|---|---|---|
| `profile.js` `initChangePasswordForm()` | `{ currentPassword, newPassword }` | `{ current_password, new_password }` |
| `profile.js` `initDisplayNameForm()` | `{ displayName }` | `{ display_name }` |
| `reset-password.html` inline script | `{ token, newPassword }` | `{ token, new_password }` |

Other frontend files already use snake_case correctly:
- `stomp-client.js`: `{ room_id, content, reply_to_id }` ✓
- `app.js`: `{ room_id, content }`, `{ user_id }`, `{ role }`, `{ content }`, `{ username }`, `{ name, description, visibility }` ✓
- `sidebar.js`: `{ user_id }` ✓
- `rooms/create.html`: `{ name, description, visibility }` ✓

---

**Bug 8 Fix — DM room shows internal name**

**File**: `src/main/java/com/bovae/yac/controller/web/ChatWebController.java`

**Changes**:
1. In `roomView()`, after the `saved-messages-` check, add a case for DIRECT rooms with `dm-` prefix
2. Look up the other member of the room (the one who is not the current user)
3. Set `displayName` to "Chat with <otherUser.displayName or otherUser.username>"

---

**Bug 9 Fix — Self-notification on message send**

**File**: `src/main/java/com/bovae/yac/ws/ChatMessageHandler.java`

**Changes**:
1. After `messageService.sendMessage()` returns the message, call `notificationService.markRoomAsRead(sender, room)` to update the sender's `lastReadWatermark` to the new message's watermark
2. This ensures `computeUnreadCount` returns 0 for the sender

---

**Bug 10 Fix — Non-member access to private rooms**

**File**: `src/main/java/com/bovae/yac/controller/web/ChatWebController.java`

**Changes**:
1. After checking `isMember`, if the user is NOT a member AND the room is not PUBLIC, redirect to `/chat` or throw a `ForbiddenException`
2. Only allow non-members to view PUBLIC rooms (read-only, with join banner)

---

**Bug 11 Fix — Search CSS class mismatch**

**File**: `src/main/resources/static/js/sidebar.js`

**Function**: `setupSearch()`

**Changes**:
1. Change `document.querySelector('.sidebar')` to `document.querySelector('.chat-sidebar')`

---

**Bug 12 Fix — Contacts shows self**

**File**: `src/main/resources/static/js/sidebar.js`

**Function**: `populateContacts()`

**Changes**:
1. Add an explicit filter to skip friendships where the resolved `friendId` equals `currentUserId`
2. Ensure both values are compared as strings: `String(friendId) === String(currentUserId)`
3. Add a guard: `if (friendId === currentUserId) { return; }` inside the `forEach`

---

**Bug 13 Fix — Room invitations not displayed (lazy fetch)**

**File**: `src/main/java/com/bovae/yac/repository/RoomInvitationRepository.java`

**Changes**:
1. Add a custom query with `JOIN FETCH` for the `findByInvitee` method:
```java
@Query("SELECT ri FROM RoomInvitation ri JOIN FETCH ri.room JOIN FETCH ri.inviter WHERE ri.invitee = :invitee")
List<RoomInvitation> findByInviteeWithRoomAndInviter(@Param("invitee") User invitee);
```
2. Update `RoomApiController.pendingInvitations()` to use the new method

Alternative: Add `@Transactional(readOnly = true)` to the controller method. The JOIN FETCH approach is preferred for performance (single query vs N+1).

---

**Bug 14 Fix — Password reset field mismatch**

**File**: `src/main/resources/templates/auth/reset-password.html`

**Changes**:
1. In the inline `<script>`, change the JSON body from `{ token: token, newPassword: newPassword }` to `{ token: token, new_password: newPassword }` to match the global SNAKE_CASE Jackson strategy
2. The `token` field is already correct (single word, no case difference)

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bugs on unfixed code, then verify the fixes work correctly and preserve existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bugs BEFORE implementing fixes. Confirm or refute the root cause analysis.

**Test Plan**: Write tests that exercise each bug condition on the unfixed code to observe failures.

**Test Cases**:
1. **LazyInit on Edit**: Call PUT `/api/rooms/{roomId}/messages/{id}` and assert response contains `senderUsername` (will throw LazyInitializationException on unfixed code)
2. **Reply Quote on Delete**: Render a message with a reply, delete the original, assert reply quote shows "Original message deleted" (will fail — quote disappears)
3. **Hover Area**: Verify CSS selector `.message-bubble:hover .message-actions` triggers opacity change (will fail — wrong selector)
4. **Catalog Clickable**: Assert room entries in catalog have `<a>` links (will fail — no links)
5. **Password Change**: POST `/api/password/change` with `{ "currentPassword": "x", "newPassword": "y" }` and assert 200 (will fail with 400 validation error)
6. **Display Name**: PUT `/api/users/me` with `{ "displayName": "New" }` and assert name changes (will fail — field not mapped)
7. **DM Display Name**: Load a DM room page and assert header shows "Chat with <name>" (will fail — shows "dm-xxx")
8. **Self Notification**: Send message, check sender's unread count is 0 (will fail — count > 0)
9. **Non-Member Access**: Access another user's Saved Messages room as non-member, assert 403/redirect (will fail — renders page)
10. **Search**: Type in sidebar search, assert items are filtered (will fail — no filtering)
11. **Contacts Self**: Load contacts, assert current user not in list (will fail — self appears)
12. **Invitations**: Invite user, fetch pending invitations, assert non-empty with room name (will fail — LazyInitializationException)
13. **Password Reset**: POST `/api/password/reset` with `{ "token": "valid", "newPassword": "newpass" }` and assert 200 (will fail with 400 validation error on `newPassword`)

**Expected Counterexamples**:
- Bug 1: 500 Internal Server Error with `LazyInitializationException` stack trace
- Bug 6/7/14: 400 Bad Request with validation errors on `@NotBlank` fields
- Bug 10: 200 OK with full message content for unauthorized user
- Bug 13: 500 or empty array due to lazy loading failure

### Fix Checking

**Goal**: Verify that for all inputs where each bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition_N(input) DO
  result := fixedFunction(input)
  ASSERT expectedBehavior_N(result)
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where none of the bug conditions hold, the fixed functions produce the same results as the original functions.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition_ANY(input) DO
  ASSERT originalFunction(input) = fixedFunction(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for normal operations, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Message Send Preservation**: Verify WebSocket message sending continues to broadcast correctly after Bug 1/9 fixes
2. **Room Access Preservation**: Verify members can still access their rooms after Bug 10 fix
3. **Profile Update Preservation**: Verify other profile fields still work after Bug 6/7 fixes
4. **Sidebar Population Preservation**: Verify sidebar loads correctly after Bug 11/12 fixes
5. **Invitation Accept Preservation**: Verify accepting invitations still works after Bug 13 fix

### Unit Tests

- Test `MessageApiController.toResponse()` with fully initialized Message entity
- Test `handleDeleteMessage()` JS function updates reply quotes
- Test CSS hover behavior scoped to `.message-bubble`
- Test `PasswordChangeRequest` deserialization with snake_case keys
- Test `UpdateProfileRequest` deserialization with snake_case keys
- Test `ChatWebController.roomView()` DM display name resolution
- Test `ChatWebController.roomView()` access control for non-members
- Test `NotificationService.markRoomAsRead()` called after send
- Test `RoomInvitationRepository.findByInviteeWithRoomAndInviter()` returns initialized entities
- Test sidebar search with correct CSS class selector

### Property-Based Tests

- Generate random message edit requests and verify no LazyInitializationException (jqwik)
- Generate random room/user combinations and verify access control correctness (jqwik)
- Generate random friendship lists and verify self-exclusion in contacts (jqwik)
- Generate random password change requests with snake_case keys and verify successful processing (jqwik)
- Generate random DM room configurations and verify display name resolution (jqwik)

### Integration Tests

- Full flow: edit message via REST → verify response contains sender info
- Full flow: delete message → verify reply quotes update in subsequent page load
- Full flow: submit password change with correct field names → verify password changed
- Full flow: non-member navigates to private room → verify redirect/denial
- Full flow: invite user → fetch pending invitations → verify complete data returned
- Full flow: send message → verify sender has 0 unread count
- Full flow: submit password reset with correct snake_case field names → verify password changed
