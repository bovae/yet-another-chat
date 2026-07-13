# Implementation Plan

- [x] 1. Write bug condition exploration tests
  - **Property 1: Bug Condition** - UX and Logic Bugs Round 3
  - **CRITICAL**: These tests MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **NOTE**: These tests encode the expected behavior — they will validate the fixes when they pass after implementation
  - **GOAL**: Surface counterexamples that demonstrate each bug exists
  - Write the following property-based tests using jqwik in a single test class `src/test/java/com/bovae/yac/property/UxLogicBugsRound3ExplorationTest.java`:
  - **Bug 1 — DM Leave Prevention**: Test that `RoomMemberService.leaveRoom()` throws `ForbiddenException` when room visibility is `DIRECT`. Use `@ForAll` to generate arbitrary user/room combinations where `room.visibility == DIRECT`. On unfixed code, the leave succeeds (returns 204) — this confirms the bug.
    - _Bug_Condition: isBugCondition(input) where input.room.visibility == DIRECT AND leaveRoom() does not check visibility_
  - **Bug 2 — WebSocket Reply-To Content**: Test that `ChatMessageHandler.sendMessage()` includes non-null `replyToSenderUsername` and `replyToContentSnippet` in the `ChatMessageResponse` when `replyToId` is non-null. On unfixed code, both fields are null — counterexample: any message with a valid reply_to_id produces null reply metadata.
    - _Bug_Condition: isBugCondition(input) where input.message.reply_to_id != null AND broadcast has null replyToSenderUsername_
  - **Bug 6 — Display Name in Chat**: Test that `MessageService.toResponse()` populates `senderDisplayName` from the sender's display name. On unfixed code, the field doesn't exist — counterexample: any message from a user with displayName set shows username instead.
    - _Bug_Condition: isBugCondition(input) where input.sender.displayName != null AND response shows username instead_
  - **Bug 7 — Password Reset 200**: Test that POST `/api/password/reset` with valid token returns HTTP 200 for unauthenticated users. On unfixed code, returns 302 redirect to /login.
    - _Bug_Condition: isBugCondition(input) where POST /api/password/reset is not in permitAll() list_
  - **Bug 9 — Ban Removes Member**: Test that `ModerationService.banUserFromRoom()` removes the banned user from `RoomMember` table. On unfixed code, the user remains a member — counterexample: banned user still in `findByRoomWithUsers()` result.
    - _Bug_Condition: isBugCondition(input) where banUserFromRoom() creates ban but does NOT remove membership_
  - **Bug 10 — Ban Blocks View Access**: Test that `ChatWebController.roomView()` denies access (403/redirect) for banned users. On unfixed code, banned users can still view — counterexample: banned user gets 200 OK.
    - _Bug_Condition: isBugCondition(input) where roomBanRepository.existsByRoomAndUser() == true AND roomView() only checks isMember_
  - **Bug 11 — Ban Blocks Send**: Test that `MessageService.sendMessage()` throws `ForbiddenException` for banned users. On unfixed code, banned users can send — counterexample: banned user's message is broadcast.
    - _Bug_Condition: isBugCondition(input) where sender is banned AND sendMessage() only checks isMember_
  - **Bug 12 — Ban List No LazyInit**: Test that GET `/api/rooms/{roomId}/bans` returns 200 with ban data (usernames populated). On unfixed code, throws `LazyInitializationException` (500).
    - _Bug_Condition: isBugCondition(input) where findByRoom() returns lazy proxies AND toBanResponse() accesses them outside session_
  - **Bug 13 — Ban List Access Control**: Test that GET `/api/rooms/{roomId}/bans` by a non-owner/non-admin returns 403. On unfixed code, returns 200 with full ban list.
    - _Bug_Condition: isBugCondition(input) where user is NOT owner/admin AND listBans() has no authorization check_
  - **Bug 14/15 — Re-Invitation After Decline**: Test that POST `/api/rooms/{roomId}/invitations` succeeds (201) for a user who previously declined. On unfixed code, throws `DataIntegrityViolationException` (500).
    - _Bug_Condition: isBugCondition(input) where previous invitation exists for (room, invitee) AND new insert violates unique constraint_
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (this is correct — it proves the bugs exist)
  - Document counterexamples found to understand root causes
  - Mark task complete when tests are written, run, and failures are documented
  - _Requirements: 1.1, 1.2, 1.6, 1.7, 1.9, 1.10, 1.11, 1.12, 1.13, 1.14, 1.15_

- [x] 2. Write preservation property tests (BEFORE implementing fixes)
  - **Property 2: Preservation** - Existing Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Write the following property-based tests using jqwik in a single test class `src/test/java/com/bovae/yac/property/UxLogicBugsRound3PreservationTest.java`:
  - **Leave Non-DM Room**: Observe on unfixed code that `leaveRoom()` succeeds for PUBLIC/PRIVATE rooms. Write property: for all rooms where `visibility != DIRECT`, leaving removes the user from membership and returns success.
    - _Preservation: Users can leave PUBLIC and PRIVATE rooms normally (3.1)_
  - **Normal Message Send**: Observe on unfixed code that messages without reply-to send correctly. Write property: for all messages where `replyToId == null`, the broadcast contains correct sender info and content.
    - _Preservation: Messages sent via WebSocket without reply-to continue to display correctly (3.2)_
  - **Username Fallback**: Observe on unfixed code that users without displayName show username. Write property: for all messages where `sender.displayName == null`, the rendered name equals the username.
    - _Preservation: Users without a display name continue to show username in chat messages (3.6)_
  - **Non-Banned User Access**: Observe on unfixed code that non-banned members can view rooms and send messages. Write property: for all users where `roomBanRepository.existsByRoomAndUser() == false` AND `isMember == true`, room access returns 200 and message send succeeds.
    - _Preservation: Non-banned users continue to access channels normally (3.8)_
  - **Owner Views Ban List**: Observe on unfixed code that room owners can view the ban list. Write property: for all requests where user is room owner, GET `/api/rooms/{roomId}/bans` returns 200.
    - _Preservation: Room owners continue to view the ban list with all banned user details (3.9)_
  - **First-Time Invitation**: Observe on unfixed code that first-time invitations create records successfully. Write property: for all (room, invitee) pairs with no existing invitation, POST creates invitation and returns 201.
    - _Preservation: First-time invitations continue to create records successfully (3.10, 3.11)_
  - **Active Tab Heartbeat**: Observe on unfixed code that heartbeats maintain ONLINE status when tab is focused. Write property: for all users with focused tab sending heartbeats, presence status is ONLINE.
    - _Preservation: Users actively using the application maintain ONLINE status (3.12)_
  - Verify tests pass on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.6, 3.8, 3.9, 3.10, 3.11, 3.12_

- [x] 3. Fix Bug 1 — DM leave prevention (RoomMemberService)

  - [x] 3.1 Implement the fix in `RoomMemberService.leaveRoom()`
    - Add a visibility check at the beginning of `leaveRoom()`: if `room.getVisibility() == RoomVisibility.DIRECT`, throw `ForbiddenException("Cannot leave a direct message room")`
    - This check must come BEFORE the existing owner check
    - _Bug_Condition: isBugCondition(input) where input.room.visibility == DIRECT AND leaveRoom() does not check visibility_
    - _Expected_Behavior: leaveRoom() throws ForbiddenException for DIRECT rooms_
    - _Preservation: Users can leave PUBLIC and PRIVATE rooms normally (3.1)_
    - _Requirements: 2.1, 3.1_

  - [x] 3.2 Verify bug condition exploration test now passes for Bug 1
    - **Property 1: Expected Behavior** - DM Leave Prevention
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The DM leave prevention test should now pass, confirming the fix works
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.1_

  - [x] 3.3 Verify preservation tests still pass for Bug 1
    - **Property 2: Preservation** - Leave Non-DM Room
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - The leave non-DM room preservation test should still pass
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)

- [x] 4. Fix Bug 2 — WebSocket reply-to showing correct content (ChatMessageHandler)

  - [x] 4.1 Implement the fix in `ChatMessageHandler.sendMessage()`
    - After resolving the `replyTo` message, extract `replyToSenderUsername` and `replyToContentSnippet`
    - Set `replyToSenderUsername = replyTo != null ? replyTo.getSender().getUsername() : null`
    - Set `replyToContentSnippet = replyTo != null ? (replyTo.getContent().length() > 100 ? replyTo.getContent().substring(0, 100) : replyTo.getContent()) : null`
    - Pass these values into the `ChatMessageResponse` constructor instead of `null`
    - _Bug_Condition: isBugCondition(input) where message.reply_to_id != null AND broadcast has null replyToSenderUsername_
    - _Expected_Behavior: broadcast includes non-null replyToSenderUsername and replyToContentSnippet when replyToId is set_
    - _Preservation: Messages without reply-to continue to display correctly (3.2)_
    - _Requirements: 2.2, 3.2_

  - [x] 4.2 Verify bug condition exploration test now passes for Bug 2
    - **Property 1: Expected Behavior** - WebSocket Reply-To Content
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.2_

  - [x] 4.3 Verify preservation tests still pass for Bug 2
    - **Property 2: Preservation** - Normal Message Send
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)

- [x] 5. Fix Bug 3 — WebSocket message deletion reply quote consistency (MessageService + app.js)

  - [x] 5.1 Implement the fix in `MessageService.toResponse()`
    - When `message.getReplyTo()` is null but the message has a non-null `reply_to_id` (dangling reference), preserve the `replyToId` in the response and set `replyToSenderUsername` to null
    - This ensures the template renders "Original message deleted" consistently for both live and server-rendered state
    - Modify the `getMessageHistory` query to use LEFT JOIN FETCH for replyTo so deleted messages result in null replyTo but replyToId is still available
    - _Bug_Condition: isBugCondition(input) where message is deleted AND replies reference it AND live state != server-rendered state_
    - _Expected_Behavior: reply quote shows "Original message deleted" consistently in both live and server-rendered state_
    - _Preservation: Messages without replies that are deleted continue to be removed normally (3.3)_
    - _Requirements: 2.3, 3.3_

- [x] 6. Fix Bug 4 — Duplicate "Saved Messages" in sidebar (sidebar.js)

  - [x] 6.1 Implement the fix in `sidebar.js` `populateRooms()`
    - In the `rooms.forEach()` loop, before appending to `directList`, skip the Saved Messages room:
    - `if (room.visibility === 'DIRECT' && !room.other_username && !room.other_display_name) { return; }`
    - This filters out the Saved Messages room since it already has a dedicated static button
    - _Bug_Condition: isBugCondition(input) where sidebar has static button AND populateRooms() also renders Saved Messages in direct-chat-list_
    - _Expected_Behavior: only ONE "Saved Messages" entry visible in sidebar_
    - _Preservation: Saved Messages functionality continues to work (3.4)_
    - _Requirements: 2.4, 3.4_

- [x] 7. Fix Bug 5 — Contacts showing self instead of friend (sidebar.js)

  - [x] 7.1 Implement the fix in `sidebar.js` `populateContacts()`
    - Ensure `getCurrentUserId()` reliably returns the user ID as a string matching the API format
    - Add defensive handling: if `currentUserId` is falsy, skip the self-filter but log a warning
    - Verify that `window.YAC_USER.id` is set correctly in the page template and matches the format returned by the friendship API
    - Use strict string comparison: `String(friendship.requester_id) === String(currentUserId)`
    - _Bug_Condition: isBugCondition(input) where getCurrentUserId() returns mismatched format AND wrong user is selected as friend_
    - _Expected_Behavior: contacts consistently display the correct friend (not self)_
    - _Preservation: All friends continue to display correctly (3.5)_
    - _Requirements: 2.5, 3.5_

- [x] 8. Fix Bug 6 — Display name not used in chats (ChatMessageResponse + MessageService + room.html + app.js)

  - [x] 8.1 Add `senderDisplayName` field to `ChatMessageResponse` record
    - Add `String senderDisplayName` field to the record after `senderUsername`
    - _Requirements: 2.6_

  - [x] 8.2 Update `MessageService.toResponse()` to populate `senderDisplayName`
    - Set `senderDisplayName = message.getSender().getDisplayName()` in the response construction
    - _Requirements: 2.6_

  - [x] 8.3 Update `ChatMessageHandler.sendMessage()` to include `senderDisplayName`
    - Include `sender.getDisplayName()` in the `ChatMessageResponse` constructor
    - _Requirements: 2.6_

  - [x] 8.4 Update `room.html` template to prefer display name
    - Change message header from `msg.senderUsername()` to `msg.senderDisplayName() != null ? msg.senderDisplayName() : msg.senderUsername()`
    - Update avatar initial to use display name first character if available
    - _Requirements: 2.6_

  - [x] 8.5 Update `app.js` `onNewMessage()` to use display name
    - Use `msg.sender_display_name || msg.sender_username` for the message header rendering
    - _Requirements: 2.6_

  - [x] 8.6 Verify bug condition exploration test now passes for Bug 6
    - **Property 1: Expected Behavior** - Display Name in Chat
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.6_

  - [x] 8.7 Verify preservation tests still pass for Bug 6
    - **Property 2: Preservation** - Username Fallback
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - _Bug_Condition: isBugCondition(input) where sender.displayName != null AND response shows username instead_
    - _Expected_Behavior: chat message header shows displayName when set, username when not_
    - _Preservation: Users without display name continue to show username (3.6)_
    - _Requirements: 2.6, 3.6_

- [x] 9. Fix Bug 7 — Password reset returning 302 instead of 200 (SecurityConfig)

  - [x] 9.1 Implement the fix in `SecurityConfig`
    - Add `/api/password/reset` and `/api/password/reset-request` to the `permitAll()` request matchers
    - _Bug_Condition: isBugCondition(input) where POST /api/password/reset is not in permitAll() AND Spring Security redirects to /login_
    - _Expected_Behavior: POST /api/password/reset returns 200 OK for unauthenticated users_
    - _Preservation: Password change endpoint continues to work for authenticated users (3.7)_
    - _Requirements: 2.7, 3.7_

  - [x] 9.2 Verify bug condition exploration test now passes for Bug 7
    - **Property 1: Expected Behavior** - Password Reset 200
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.7_

  - [x] 9.3 Verify preservation tests still pass for Bug 7
    - **Property 2: Preservation** - Authenticated Password Change
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)

- [x] 10. Fix Bug 8 — UI hover issue for full line visibility (chat.css)

  - [x] 10.1 Implement the CSS fix in `chat.css`
    - Remove or adjust the `.message-item:hover` rule that causes layout shift
    - Remove the `padding` and negative `margin` properties that cause content shift
    - Keep only the background color change on hover: `background: #f0f2f5; border-radius: 0.375rem;`
    - _Bug_Condition: isBugCondition(input) where .message-item:hover applies padding + negative margin causing layout shift_
    - _Expected_Behavior: full line content visible without hover, hover only changes background_
    - _Requirements: 2.8_

- [x] 11. Fix Bugs 9-11 — Ban enforcement: member removal, view access, send messages

  - [x] 11.1 Fix Bug 9: Update `ModerationService.banUserFromRoom()` to remove membership
    - After creating the `RoomBan` record, also remove the user from `RoomMember` table
    - Use `roomMemberRepository.findById(new RoomMemberId(room.getId(), targetUser.getId())).ifPresent(roomMemberRepository::delete)`
    - _Bug_Condition: isBugCondition(input) where banUserFromRoom() creates ban but does NOT remove membership_
    - _Expected_Behavior: banned user is removed from RoomMember table_
    - _Requirements: 2.9_

  - [x] 11.2 Fix Bug 10: Update `ChatWebController.roomView()` to check ban status
    - After resolving room and user, add: `if (roomBanRepository.existsByRoomAndUser(room, user)) { throw new ForbiddenException("You are banned from this room"); }`
    - Inject `RoomBanRepository` into the controller
    - _Bug_Condition: isBugCondition(input) where user is banned AND roomView() only checks isMember_
    - _Expected_Behavior: banned user gets 403 Forbidden when accessing room_
    - _Requirements: 2.10_

  - [x] 11.3 Fix Bug 11: Update `MessageService.sendMessage()` to check ban status
    - After the `isMember` check, add ban check for non-DIRECT rooms: `if (room.getVisibility() != RoomVisibility.DIRECT && roomBanRepository.existsByRoomAndUser(room, sender)) { throw new ForbiddenException("You are banned from this room"); }`
    - Inject `RoomBanRepository` into `MessageService`
    - _Bug_Condition: isBugCondition(input) where sender is banned AND sendMessage() only checks isMember_
    - _Expected_Behavior: banned user gets ForbiddenException when sending messages_
    - _Requirements: 2.11_

  - [x] 11.4 Verify bug condition exploration tests now pass for Bugs 9-11
    - **Property 1: Expected Behavior** - Ban Enforcement
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms bugs are fixed)
    - _Requirements: 2.9, 2.10, 2.11_

  - [x] 11.5 Verify preservation tests still pass for Bugs 9-11
    - **Property 2: Preservation** - Non-Banned User Access
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - _Preservation: Non-banned users continue to access channels normally (3.8)_

- [x] 12. Fix Bug 12 — LazyInitializationException on ban list (RoomBanRepository + RoomBanApiController)

  - [x] 12.1 Add eager-fetch query to `RoomBanRepository`
    - Add `@Query("SELECT rb FROM RoomBan rb JOIN FETCH rb.user JOIN FETCH rb.bannedBy WHERE rb.room = :room") List<RoomBan> findByRoomWithUserAndBannedBy(@Param("room") Room room);`
    - _Requirements: 2.12_

  - [x] 12.2 Update `RoomBanApiController.listBans()` to use the new query
    - Replace `roomBanRepository.findByRoom(room)` with `roomBanRepository.findByRoomWithUserAndBannedBy(room)`
    - _Bug_Condition: isBugCondition(input) where findByRoom() returns lazy proxies AND toBanResponse() accesses them outside session_
    - _Expected_Behavior: ban list returns 200 with populated usernames, no LazyInitializationException_
    - _Requirements: 2.12_

  - [x] 12.3 Verify bug condition exploration test now passes for Bug 12
    - **Property 1: Expected Behavior** - Ban List No LazyInit
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.12_

- [x] 13. Fix Bug 13 — Ban list access restricted to owner/admin only (RoomBanApiController)

  - [x] 13.1 Add authorization check to `RoomBanApiController.listBans()`
    - After resolving user and room, verify the user is the room owner or an admin
    - If not owner, check `RoomMember` role — if not ADMIN or OWNER, throw `ForbiddenException("Only room owners and admins can view the ban list")`
    - Inject `RoomMemberRepository` into the controller if not already present
    - _Bug_Condition: isBugCondition(input) where user is NOT owner/admin AND listBans() has no authorization check_
    - _Expected_Behavior: non-owner/non-admin gets 403 Forbidden_
    - _Preservation: Room owners continue to view the ban list (3.9)_
    - _Requirements: 2.13, 3.9_

  - [x] 13.2 Verify bug condition exploration test now passes for Bug 13
    - **Property 1: Expected Behavior** - Ban List Access Control
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.13_

  - [x] 13.3 Verify preservation tests still pass for Bug 13
    - **Property 2: Preservation** - Owner Views Ban List
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)

- [x] 14. Fix Bugs 14-15 — Invitation re-send after decline (RoomInvitationApiController)

  - [x] 14.1 Implement the fix in `RoomInvitationApiController.inviteUser()`
    - Before creating a new invitation, check for and delete any existing invitation for the same (room, invitee) pair: `roomInvitationRepository.findByRoomAndInvitee(room, invitee).ifPresent(roomInvitationRepository::delete)`
    - Flush the delete before the save to avoid constraint violations within the same transaction
    - Add `@Transactional` to the method to ensure atomicity
    - _Bug_Condition: isBugCondition(input) where previous invitation exists for (room, invitee) AND new insert violates unique constraint_
    - _Expected_Behavior: re-invitation succeeds (201) by deleting old record first_
    - _Preservation: First-time invitations continue to create records successfully (3.10, 3.11)_
    - _Requirements: 2.14, 2.15, 3.10, 3.11_

  - [x] 14.2 Verify bug condition exploration test now passes for Bugs 14-15
    - **Property 1: Expected Behavior** - Re-Invitation After Decline
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.14, 2.15_

  - [x] 14.3 Verify preservation tests still pass for Bugs 14-15
    - **Property 2: Preservation** - First-Time Invitation
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)

- [x] 15. Fix Bugs 16-17 — Presence/AFK tracking: tab focus/close (presence.js + PresenceService)

  - [x] 15.1 Fix Bug 16: Update `presence.js` to stop heartbeats when tab is hidden
    - Add or modify the `visibilitychange` listener to stop the heartbeat timer when `document.hidden` is true
    - When tab becomes visible again, restart heartbeats and send an immediate active heartbeat
    - ```javascript
      document.addEventListener('visibilitychange', function () {
        if (document.hidden) { stopHeartbeat(); }
        else { startHeartbeat(); sendImmediateActiveHeartbeat(); }
      });
      ```
    - _Bug_Condition: isBugCondition(input) where document.hidden == true AND heartbeatTimer continues running_
    - _Expected_Behavior: heartbeats stop when tab is hidden, resume when tab is visible_
    - _Requirements: 2.16_

  - [x] 15.2 Fix Bug 17: Reduce presence TTL in `PresenceService`
    - Change `PRESENCE_TTL` from 90 seconds to 45 seconds: `private static final Duration PRESENCE_TTL = Duration.ofSeconds(45);`
    - This ensures faster OFFLINE transition after last heartbeat (max 45s instead of 90s)
    - _Bug_Condition: isBugCondition(input) where TTL is 90s AND user stays ONLINE too long after tab close_
    - _Expected_Behavior: user transitions to OFFLINE within ~45 seconds of last heartbeat_
    - _Preservation: Users actively using the application maintain ONLINE status (3.12)_
    - _Requirements: 2.17, 3.12_

  - [x] 15.3 Verify preservation tests still pass for Bugs 16-17
    - **Property 2: Preservation** - Active Tab Heartbeat
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)

- [x] 16. Fix Bug 18 — Sidebar presence status display (presence.js + stomp-client.js)

  - [x] 16.1 Update `stomp-client.js` to trigger presence subscriptions on STOMP connect
    - In the `onConnect` callback of `subscribeToChannels()`, trigger presence subscriptions:
    - `if (window.YAC && window.YAC.presence && window.YAC.presence.subscribeToAllVisibleUsers) { window.YAC.presence.subscribeToAllVisibleUsers(); }`
    - _Requirements: 2.18_

  - [x] 16.2 Update `presence.js` to ensure `fetchInitialPresence()` works correctly
    - Verify the `/api/presence` endpoint returns the correct format matching what `updatePresenceDot()` expects (`user_id` and `status` fields)
    - Ensure `fetchInitialPresence()` is called AFTER sidebar contacts are rendered
    - Add error handling for the fetch call to avoid silent failures
    - _Bug_Condition: isBugCondition(input) where subscriptions happen before STOMP is connected OR fetchInitialPresence() fails silently_
    - _Expected_Behavior: sidebar shows correct presence status without requiring navigation into a chat_
    - _Preservation: Presence status in chat room member lists continues to display correctly (3.13)_
    - _Requirements: 2.18, 3.13_

- [x] 17. Checkpoint — Ensure all tests pass
  - Run the full test suite: `./mvnw verify`
  - Ensure all exploration tests (task 1) now PASS after all fixes
  - Ensure all preservation tests (task 2) still PASS after all fixes
  - Ensure no existing tests are broken
  - Ask the user if questions arise
