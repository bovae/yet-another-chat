# Implementation Plan

- [x] 1. Write bug condition exploration tests
  - **Property 1: Bug Condition** - Multi-Bug Exploration (LazyInit, snake_case, Access Control, DOM Updates)
  - **CRITICAL**: These tests MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **NOTE**: These tests encode the expected behavior — they will validate the fixes when they pass after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bugs exist
  - **Scoped PBT Approach**: For each deterministic bug, scope the property to the concrete failing case(s)
  - Test 1a (Bug 1): Call `messageRepository.findByIdWithSender(id)` — method does not exist yet, so test that `editMessage()` via REST PUT `/api/rooms/{roomId}/messages/{id}` returns 200 with populated `senderUsername` field (will fail with 500 LazyInitializationException)
  - Test 1b (Bug 6): POST `/api/password/change` with `{ "current_password": "old", "new_password": "new" }` snake_case keys — currently frontend sends camelCase so simulate the broken path: POST with `{ "currentPassword": "old", "newPassword": "new" }` and assert it fails with 400 (confirms bug)
  - Test 1c (Bug 7): PUT `/api/users/me` with `{ "displayName": "New" }` camelCase key — assert field is NOT mapped (confirms bug)
  - Test 1d (Bug 10): Non-member GET `/chat/rooms/{privateRoomId}` — assert access is denied (will fail — currently renders page)
  - Test 1e (Bug 13): Fetch pending invitations for user with lazy room/inviter — assert non-empty response with room name (will fail with LazyInitializationException)
  - Test 1f (Bug 14): POST `/api/password/reset` with `{ "token": "valid", "newPassword": "pass" }` camelCase — assert 400 (confirms bug)
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (this is correct — it proves the bugs exist)
  - Document counterexamples found to understand root cause
  - Mark task complete when tests are written, run, and failures are documented
  - _Requirements: 1.1, 1.6, 1.7, 1.10, 1.13, 1.14_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Existing Functionality Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: Normal message send via WebSocket continues to broadcast to all members on unfixed code
  - Observe: Members can access their own rooms and see full message history on unfixed code
  - Observe: Sidebar populates rooms and contacts correctly on unfixed code
  - Observe: Accepting room invitations adds user as member on unfixed code
  - Observe: Session termination invalidates sessions on unfixed code
  - Write property-based tests:
    - For all valid message send requests by members, message is broadcast and response contains sender info
    - For all room members accessing their rooms, page renders with message history
    - For all non-reply message deletions, only the deleted message element is removed (no side effects)
    - For all contacts in sidebar, clicking initiates DM correctly
  - Verify tests pass on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13_

- [x] 3. Fix Bug 1 — LazyInitializationException on message edit

  - [x] 3.1 Add `findByIdWithSender()` to MessageRepository
    - Add `@Query("SELECT m FROM Message m JOIN FETCH m.sender WHERE m.id = :id")` method
    - Method signature: `Optional<Message> findByIdWithSender(@Param("id") UUID id)`
    - Only JOIN FETCH `m.sender` — no room fetch needed (room ID comes from URL path)
    - _Bug_Condition: isBugCondition(input) where editMessage() accesses message.getSender() on lazy proxy_
    - _Expected_Behavior: findByIdWithSender returns Message with initialized sender entity_
    - _Preservation: All other findById usages remain unchanged_
    - _Requirements: 2.1_

  - [x] 3.2 Update `MessageService.editMessage()` to use `findByIdWithSender()`
    - Replace `messageRepository.findById(messageId)` with `messageRepository.findByIdWithSender(messageId)`
    - This ensures `MessageApiController.toResponse()` can safely call `message.getSender().getUsername()`
    - _Requirements: 2.1_

- [x] 4. Fix Bug 2 — Reply quotes not updating on WebSocket delete

  - [x] 4.1 Update `handleDeleteMessage()` in `app.js` to update reply quotes
    - After `messageItem.remove()`, scan DOM for all `.reply-quote` elements inside `.message-item` elements
    - For server-rendered messages: check if the reply quote references the deleted message by looking at sibling `data-message-id` attributes or reply-to structure
    - For dynamically-rendered messages: the reply quote's parent `.message-item` has a reply-to reference stored in the DOM structure
    - Find all message items whose reply references the deleted message ID (check `data-message-id` on reply buttons or the reply quote's associated message)
    - Update matching reply quotes: set text content to "Original message deleted", remove sender name
    - _Bug_Condition: handleDeleteMessage() only calls messageItem.remove() without updating reply quotes_
    - _Expected_Behavior: All reply quotes referencing deleted message show "Original message deleted"_
    - _Preservation: Messages without reply references are unaffected (3.2)_
    - _Requirements: 2.2, 3.2_

- [x] 5. Fix Bug 3 — Message action buttons hover area too wide

  - [x] 5.1 Change CSS hover selector in `chat.css`
    - Change `.message-item:hover .message-actions { opacity: 1 }` to `.message-bubble:hover .message-actions { opacity: 1 }`
    - Keep the `.message-actions { opacity: 0; transition: opacity 0.2s }` base rule unchanged
    - _Bug_Condition: CSS rule triggers on .message-item (full row) instead of .message-bubble_
    - _Expected_Behavior: Action buttons only appear when hovering over the bubble element_
    - _Preservation: Fade-in transition effect preserved (3.3)_
    - _Requirements: 2.3, 3.3_

- [x] 6. Fix Bug 4 — Catalog not clickable, Join shown for members

  - [x] 6.1 Pass membership info to catalog template in `RoomWebController`
    - Inject `Principal` parameter into the `catalog()` method
    - Look up current user from `userRepository.findByEmail(principal.getName())`
    - Query `roomMemberRepository` for user's joined room IDs (or use a Set)
    - Add `joinedRoomIds` set to the model
    - _Requirements: 2.4_

  - [x] 6.2 Update `catalog.html` to make entries clickable and membership-aware
    - Wrap room name/description in `<a th:href="@{/chat/rooms/{id}(id=${entry.id()})}">` link
    - Conditionally show "Joined" badge (`<span class="badge bg-success">Joined</span>`) when `joinedRoomIds.contains(entry.id())`
    - Show "Join" button only when user is NOT already a member
    - _Bug_Condition: Room entries are plain divs with no links; Join always shown_
    - _Expected_Behavior: Entries are clickable links; "Joined" badge for members_
    - _Preservation: Catalog search and pagination unchanged (3.4)_
    - _Requirements: 2.4, 3.4_

- [x] 7. Fix Bug 5 — Sessions page missing browser details and IP

  - [x] 7.1 Extend `AuthService.SessionInfo` record with `userAgent` and `ipAddress`
    - Add `String userAgent` and `String ipAddress` fields to the record
    - In `listSessions()`, extract from session attributes: `session.getAttribute("SPRING_SECURITY_CONTEXT")` or standard attribute keys
    - Spring Security stores remote address in `WebAuthenticationDetails` — extract from `SecurityContext` stored in session
    - If attributes not available, use null/empty string
    - _Requirements: 2.5_

  - [x] 7.2 Update `sessions.html` template to display browser and IP info
    - Add User-Agent display (or parsed browser name) below session info
    - Add IP address display
    - Handle null gracefully with `th:if` checks
    - _Bug_Condition: Template only shows creation/last-accessed times_
    - _Expected_Behavior: Template shows browser details and IP when available_
    - _Preservation: Session termination continues to work (3.5)_
    - _Requirements: 2.5, 3.5_

- [x] 8. Fix Bugs 6, 7, 14 — snake_case field name audit (3 frontend locations)

  - [x] 8.1 Fix `profile.js` `initChangePasswordForm()` — send snake_case keys
    - Change `JSON.stringify({ currentPassword: currentPassword, newPassword: newPassword })` to `JSON.stringify({ current_password: currentPassword, new_password: newPassword })`
    - _Bug_Condition: Frontend sends camelCase but Jackson SNAKE_CASE expects snake_case_
    - _Expected_Behavior: Password change succeeds without validation errors_
    - _Requirements: 2.6_

  - [x] 8.2 Fix `profile.js` `initDisplayNameForm()` — send snake_case key
    - Change `JSON.stringify({ displayName: displayName })` to `JSON.stringify({ display_name: displayName })`
    - _Bug_Condition: Frontend sends "displayName" but Jackson expects "display_name"_
    - _Expected_Behavior: Display name update persists correctly_
    - _Requirements: 2.7_

  - [x] 8.3 Fix `reset-password.html` inline script — send snake_case key
    - Change `JSON.stringify({ token: token, newPassword: newPassword })` to `JSON.stringify({ token: token, new_password: newPassword })`
    - The `token` field is correct (single word, no case difference)
    - _Bug_Condition: Frontend sends "newPassword" but Jackson expects "new_password"_
    - _Expected_Behavior: Password reset succeeds without validation errors_
    - _Preservation: Password reset flow continues to work for valid tokens (3.6)_
    - _Requirements: 2.14, 3.6_

- [x] 9. Fix Bug 8 — DM room shows internal name instead of "Chat with <name>"

  - [x] 9.1 Resolve DM display name in `ChatWebController.roomView()`
    - After the `saved-messages-` prefix check, add an `else if` for DIRECT rooms with `dm-` prefix
    - Look up room members via `roomMemberService.listMembers(room)`
    - Find the other member (not the current user) from the members list
    - Set `displayName` to `"Chat with " + (otherUser.displayName or otherUser.username)`
    - Handle edge case: if no other member found (shouldn't happen), fall back to room name
    - _Bug_Condition: DIRECT room with "dm-" prefix falls through to room.getName()_
    - _Expected_Behavior: Header shows "Chat with <other_user_display_name_or_username>"_
    - _Preservation: Saved Messages rooms still show "Saved Messages" (3.8)_
    - _Requirements: 2.8, 3.8_

- [x] 10. Fix Bug 9 — Self-notification on message send

  - [x] 10.1 Update sender's `lastReadWatermark` after sending in `ChatMessageHandler`
    - After `messageService.sendMessage()` returns the message, call `notificationService.markRoomAsRead(sender, room)`
    - This sets sender's watermark to `room.getNextWatermark() - 1` which equals the new message's watermark
    - Ensures `computeUnreadCount` returns 0 for the sender
    - _Bug_Condition: Sender's lastReadWatermark not updated after sending → unread badge appears_
    - _Expected_Behavior: Sender's lastReadWatermark equals new message watermark → no unread badge_
    - _Preservation: Recipients continue to receive unread notification badges (3.9)_
    - _Requirements: 2.9, 3.9_

- [x] 11. Fix Bug 10 — Non-member access to private/direct rooms

  - [x] 11.1 Add access control check in `ChatWebController.roomView()`
    - After computing `isMember`, add: if `!isMember && room.getVisibility() != RoomVisibility.PUBLIC`, throw `ForbiddenException` or redirect to `/chat`
    - Only PUBLIC rooms allow non-member viewing (read-only with join banner)
    - PRIVATE and DIRECT rooms require membership
    - _Bug_Condition: roomView() renders content for non-members of non-PUBLIC rooms_
    - _Expected_Behavior: Non-members denied access to PRIVATE/DIRECT rooms_
    - _Preservation: Members continue to access their rooms normally (3.10)_
    - _Requirements: 2.10, 3.10_

- [x] 12. Fix Bug 11 — Chat search CSS class mismatch

  - [x] 12.1 Fix selector in `sidebar.js` `setupSearch()`
    - Change `document.querySelector('.sidebar')` to `document.querySelector('.chat-sidebar')`
    - The actual sidebar container in `room.html` uses class `chat-sidebar`
    - _Bug_Condition: querySelector('.sidebar') returns null because class is "chat-sidebar"_
    - _Expected_Behavior: Search input found and event listener attached; filtering works_
    - _Preservation: Sidebar continues to populate correctly (3.11)_
    - _Requirements: 2.11, 3.11_

- [x] 13. Fix Bug 12 — Contacts shows self

  - [x] 13.1 Add self-exclusion guard in `sidebar.js` `populateContacts()`
    - Inside the `friends.forEach` callback, after resolving `friendId`, add: `if (String(friendId) === String(currentUserId)) { return; }`
    - This skips rendering the current user in the contacts list
    - Use `String()` coercion to handle any type mismatch between UUID formats
    - _Bug_Condition: friendId may equal currentUserId but comparison fails or is missing_
    - _Expected_Behavior: Current user never appears in their own contacts list_
    - _Preservation: Clicking contacts still initiates DM (3.12)_
    - _Requirements: 2.12, 3.12_

- [x] 14. Fix Bug 13 — Room invitations not displayed (lazy fetch)

  - [x] 14.1 Add `findByInviteeWithRoomAndInviter()` to `RoomInvitationRepository`
    - Add `@Query("SELECT ri FROM RoomInvitation ri JOIN FETCH ri.room JOIN FETCH ri.inviter WHERE ri.invitee = :invitee")`
    - Method signature: `List<RoomInvitation> findByInviteeWithRoomAndInviter(@Param("invitee") User invitee)`
    - _Requirements: 2.13_

  - [x] 14.2 Update pending invitations endpoint to use new repository method
    - In `RoomApiController.pendingInvitations()`, replace `findByInvitee(user)` with `findByInviteeWithRoomAndInviter(user)`
    - This ensures `inv.getRoom().getName()` and `inv.getInviter().getUsername()` work without LazyInitializationException
    - _Bug_Condition: findByInvitee returns lazy proxies that fail outside transaction_
    - _Expected_Behavior: Invitations returned with fully initialized room and inviter data_
    - _Preservation: Accepting invitations continues to work (3.13)_
    - _Requirements: 2.13, 3.13_

- [x] 15. Verify bug condition exploration tests now pass

  - [x] 15.1 Re-run bug condition exploration tests after all fixes
    - **Property 1: Expected Behavior** - All Bug Conditions Resolved
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - The tests from task 1 encode the expected behavior for each bug
    - When these tests pass, it confirms all expected behaviors are satisfied:
      - Bug 1: editMessage returns valid response with senderUsername
      - Bug 6/7/14: snake_case keys are correctly mapped by Jackson
      - Bug 10: Non-member access is denied
      - Bug 13: Pending invitations return complete data
    - Run bug condition exploration tests from step 1
    - **EXPECTED OUTCOME**: Tests PASS (confirms bugs are fixed)
    - _Requirements: 2.1, 2.6, 2.7, 2.10, 2.13, 2.14_

  - [x] 15.2 Re-run preservation tests after all fixes
    - **Property 2: Preservation** - No Regressions
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - Confirm all tests still pass after fixes
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13_

- [x] 16. Checkpoint - Ensure all tests pass
  - Run full test suite (`./mvnw verify`)
  - Ensure all unit tests, integration tests, and property-based tests pass
  - Ensure no regressions in existing functionality
  - Ask the user if questions arise
