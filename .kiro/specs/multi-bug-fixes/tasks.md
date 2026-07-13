# Implementation Plan

- [x] 1. Write bug condition exploration tests
  - **Property 1: Bug Condition** - Seven Bugs Exist on Unfixed Code
  - **CRITICAL**: Write these tests BEFORE implementing any fixes
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **GOAL**: Confirm each bug manifests as described in the design document
  - Tests to write (one test method per bug):
    - Bug 1: PUT `/api/rooms/{roomId}/messages/{id}` with body `{"content": "test"}` returns 400
    - Bug 2: Send WebSocket message in multi-member room triggers LazyInitializationException
    - Bug 3: `subscribeToAllVisibleUsers()` does NOT call `fetchInitialPresence()` (verify via code inspection or mock)
    - Bug 4: After `resetPassword()`, user's password hash is unchanged in DB
    - Bug 5: `populateRoomInvitations()` renders div with class `collapse` without `show`
    - Bug 6: Saved Messages room header displays raw `saved-messages-{UUID}` name
    - Bug 7: GET `/profile/sessions` returns HTTP 500
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (confirms bugs exist)
  - Document failures to confirm root causes match design hypotheses
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12_

- [x] 2. Write preservation tests (BEFORE implementing fixes)
  - **Property 2: Preservation** - Existing Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology — run on unfixed code first
  - Tests to write:
    - WebSocket send with full `ChatMessageRequest` (roomId + content) succeeds in single-member room
    - REST `POST /api/rooms/{roomId}/messages` continues to validate and create messages
    - Password change flow (`POST /api/password/change`) persists new hash correctly
    - Non-DIRECT rooms display their actual stored name in templates
    - Other Thymeleaf templates (profile, rooms) render without errors
    - Friend-to-friend invitations display correctly in sidebar
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (confirms baseline behavior to preserve)
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12_

- [x] 3. Implement Bug 1 fix — Create EditMessageRequest DTO

  - [x] 3.1 Create `EditMessageRequest` record and update controller
    - Create `src/main/java/com/bovae/yac/model/dto/EditMessageRequest.java`
    - Record with single field: `@NotBlank @MaxByteSize(3072) String content`
    - Update `MessageApiController.editMessage()` to use `@Valid @RequestBody EditMessageRequest` instead of `ChatMessageRequest`
    - _Bug_Condition: request body has no roomId field, roomId comes from path variable_
    - _Expected_Behavior: edit accepts content-only body, returns 200_
    - _Preservation: WebSocket send path still uses ChatMessageRequest with @NotNull roomId_
    - _Requirements: 2.1, 2.2, 3.1, 3.2_

- [x] 4. Implement Bug 2 fix — Use JOIN FETCH for room members

  - [x] 4.1 Replace `findByRoom()` with `findByRoomWithUsers()` in ChatMessageHandler
    - In `ChatMessageHandler.sendMessage()`, change `roomMemberRepository.findByRoom(room)` to `roomMemberRepository.findByRoomWithUsers(room)`
    - The `findByRoomWithUsers` method already exists with `JOIN FETCH rm.user`
    - _Bug_Condition: handler accesses lazy user proxy after service transaction closes_
    - _Expected_Behavior: no LazyInitializationException, message broadcasts successfully_
    - _Preservation: message persistence and watermark increment unchanged_
    - _Requirements: 2.3, 2.4, 3.3, 3.4_

- [x] 5. Implement Bug 3 fix — Fetch initial presence after subscribing

  - [x] 5.1 Add `fetchInitialPresence()` call in `subscribeToAllVisibleUsers()`
    - In `src/main/resources/static/js/presence.js`, after subscribing to all visible dots, collect user IDs into an array
    - Call `fetchInitialPresence(userIds)` with the collected IDs
    - _Bug_Condition: server-rendered member list dots never get initial status fetched_
    - _Expected_Behavior: dots update to correct color (green/yellow) on page load_
    - _Preservation: contact list presence (via populateContacts) unchanged_
    - _Requirements: 2.5, 2.6, 3.5, 3.6_

- [x] 6. Implement Bug 4 fix — Reload User entity in password reset

  - [x] 6.1 Use `userRepository.findById()` in `PasswordService.resetPassword()`
    - Replace `User user = resetToken.getUser()` with `User user = userRepository.findById(resetToken.getUser().getId()).orElseThrow()`
    - Ensures fully-loaded managed entity for reliable dirty checking
    - _Bug_Condition: lazy proxy from resetToken.getUser() doesn't persist password hash_
    - _Expected_Behavior: new password hash persisted, new password works for login_
    - _Preservation: expired/used token rejection unchanged, password change flow unchanged_
    - _Requirements: 2.7, 2.8, 3.7, 3.8_

- [x] 7. Implement Bug 5 fix — Auto-expand invitation section

  - [x] 7.1 Change collapse div to `collapse show` in `populateRoomInvitations()`
    - In `src/main/resources/static/js/sidebar.js`, change the invitation section div class from `collapse` to `collapse show`
    - _Bug_Condition: invitations exist but section is hidden (collapse without show)_
    - _Expected_Behavior: invitation section visible by default when invitations exist_
    - _Preservation: friend invitations continue displaying correctly_
    - _Requirements: 2.9, 2.10, 3.9, 3.10_

- [x] 8. Implement Bug 6 fix — Display friendly name for Saved Messages

  - [x] 8.1 Add `displayName` model attribute in `ChatWebController`
    - After loading room, check if `room.getVisibility() == DIRECT && room.getName().startsWith("saved-messages-")`
    - Add model attribute `displayName` = "Saved Messages" for such rooms, else `room.getName()`
    - _Bug_Condition: Saved Messages room displays raw UUID-based internal name_
    - _Expected_Behavior: header and title show "Saved Messages"_
    - _Requirements: 2.11, 3.11_

  - [x] 8.2 Update `room.html` template to use `displayName`
    - Change `<title>` from `th:text="${room.name} + ' - YAC'"` to `th:text="${displayName} + ' - YAC'"`
    - Change chat header `<h6>` from `th:text="${room.name}"` to `th:text="${displayName}"`
    - Change `window.YAC_ROOM` script from `name: [[${room.name}]]` to `name: [[${displayName}]]`
    - _Requirements: 2.11_

  - [x] 8.3 Update `member-list.html` to use `displayName` with fallback
    - Change room info `<p>` from `th:text="${room.name}"` to `th:text="${displayName != null ? displayName : room.name}"`
    - _Requirements: 2.11, 3.11_

- [x] 9. Implement Bug 7 fix — Rename Thymeleaf iteration variable

  - [x] 9.1 Rename `session` → `sess` in `sessions.html`
    - Change `th:each="session : ${sessions}"` to `th:each="sess : ${sessions}"`
    - Update all references in the loop: `sess.creationTime()`, `sess.lastAccessedTime()`, `sess.sessionId()`, etc.
    - _Bug_Condition: "session" is a Thymeleaf reserved variable name_
    - _Expected_Behavior: sessions page renders successfully with session list_
    - _Preservation: session details (device, last active, current indicator) display correctly_
    - _Requirements: 2.12, 3.12_

- [x] 10. Verify all fixes

  - [x] 10.1 Verify bug condition exploration tests now pass
    - **Property 1: Expected Behavior** - All Seven Bugs Resolved
    - Re-run the SAME tests from task 1 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms all bugs are fixed)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12_

  - [x] 10.2 Verify preservation tests still pass
    - **Property 2: Preservation** - Existing Behavior Unchanged
    - Re-run the SAME tests from task 2 — do NOT write new tests
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12_

- [x] 11. Checkpoint - Ensure all tests pass
  - Run full test suite: `./mvnw test`
  - Ensure no regressions in existing tests
  - Ask the user if questions arise
