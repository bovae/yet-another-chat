# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Catalog Join Does Not Create Membership
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: For any non-member user and any public room, simulate the catalog join flow and assert membership is created
  - Create `RoomJoinMembershipBugConditionPropertyTest.java` in `src/test/java/com/bovae/yac/property/`
  - Use `@JqwikSpringSupport`, `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfig.class)` — matching existing test conventions
  - Generate arbitrary public rooms and non-member users
  - **Bug Condition from design**: `isBugCondition(input)` where `input.room.visibility = PUBLIC AND NOT roomMemberRepository.existsByRoomAndUser(input.room, input.user) AND input.action = "catalog_join_click"`
  - **Test the current catalog join flow**: GET `/chat/rooms/{id}` (simulating the current `<a>` link behavior) and assert that `roomMemberRepository.existsByRoomAndUser()` returns `true` after the action
  - **Also test**: The model should contain `isMember` attribute set to `true` after joining
  - The test assertions encode the Expected Behavior: after clicking "Join", a `RoomMember` with `MEMBER` role exists and the user lands on the chat view as a member
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct — it proves the bug exists: the GET link creates no membership, and `isMember` is not in the model)
  - Document counterexamples found to understand root cause
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Existing Member Room Access Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Create `RoomJoinMembershipPreservationPropertyTest.java` in `src/test/java/com/bovae/yac/property/`
  - Use `@JqwikSpringSupport`, `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfig.class)`
  - **Observe behavior on UNFIXED code for non-buggy inputs** (cases where `isBugCondition` returns false):
    - Observe: Existing member (OWNER/ADMIN/MEMBER) GETs `/chat/rooms/{id}` → returns 200 with room view, messages visible, message input active
    - Observe: `POST /api/rooms/{id}/join` for non-member + public room → returns 200, membership created (API join still works)
    - Observe: Banned user `POST /api/rooms/{id}/join` → returns 403 (ban enforcement preserved)
    - Observe: Catalog browsing `GET /rooms/catalog` → returns 200 with room listings, search, pagination unchanged
  - **Property-based tests capturing observed behavior**:
    - Property: For all existing members with any role (OWNER, ADMIN, MEMBER), GET `/chat/rooms/{id}` returns 200 and renders `chat/room` view with `room`, `members`, `messages`, `currentUser` model attributes
    - Property: For all non-member + public + non-banned users, `POST /api/rooms/{id}/join` creates a `RoomMember` with `MEMBER` role
    - Property: For all banned users, `POST /api/rooms/{id}/join` returns 403
    - Property: For all search terms, `GET /rooms/catalog` returns 200 with `catalog` and `search` model attributes
  - Verify tests pass on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 3. Fix for room join membership bug

  - [x] 3.1 Add `POST /rooms/{id}/join` endpoint to `RoomWebController`
    - Inject `RoomMemberService` and `UserRepository` into `RoomWebController` (currently only has `RoomService`)
    - Add `joinRoom(@PathVariable UUID id, Principal principal)` method with `@PostMapping("/{id}/join")`
    - Resolve the authenticated user via `userRepository.findByEmail(principal.getName())`
    - Resolve the room via `roomService.getRoomById(id)`
    - Call `roomMemberService.joinPublicRoom(room, user)` — this handles ban checks (`ForbiddenException`) and duplicate membership (`ConflictException`) internally
    - Return `"redirect:/chat/rooms/" + id`
    - _Bug_Condition: isBugCondition(input) where input.room.visibility = PUBLIC AND NOT member AND action = "catalog_join_click"_
    - _Expected_Behavior: POST /rooms/{id}/join creates RoomMember with MEMBER role, then redirects to /chat/rooms/{id}_
    - _Preservation: API join (POST /api/rooms/{id}/join), banned user rejection, existing member access unchanged_
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 3.3_

  - [x] 3.2 Change catalog "Join" button from `<a>` link to form POST
    - In `src/main/resources/templates/rooms/catalog.html`, replace `<a th:href="@{/chat/rooms/{id}(id=${entry.id()})}" class="btn btn-outline-primary btn-sm">Join</a>` with a `<form>` that POSTs to `/rooms/{entry.id()}/join`
    - The form contains a submit button styled as the existing "Join" button (`btn btn-outline-primary btn-sm`)
    - CSRF token is auto-included by Thymeleaf's `th:action`
    - _Bug_Condition: Catalog "Join" was a plain <a> link navigating directly to chat view without creating membership_
    - _Expected_Behavior: Form POST hits /rooms/{id}/join, creates membership, then redirects to chat view_
    - _Requirements: 2.1, 2.3_

  - [x] 3.3 Add `isMember` model attribute to `ChatWebController.roomView()`
    - In `ChatWebController.roomView()`, after resolving room and user, call `roomMemberService.isMember(room, user)`
    - Add `model.addAttribute("isMember", isMember)` to pass the boolean to the template
    - No auto-join logic — non-members simply see the room in read-only mode
    - _Bug_Condition: roomView() never checked membership, so template had no way to conditionally render join prompt_
    - _Expected_Behavior: model contains isMember boolean, template can conditionally show/hide message input and join banner_
    - _Preservation: Existing members get isMember=true, no behavior change for them_
    - _Requirements: 2.1, 2.2, 3.1, 3.5_

  - [x] 3.4 Add conditional join banner and disabled input for non-members in `room.html`
    - When `isMember` is `false` and room visibility is `PUBLIC`, display a join banner above the message input with text "You're not a member of this room" and a "Join Room" button that submits a form POST to `/rooms/{room.id}/join`
    - When `isMember` is `false`, hide the message input fragment (`fragments/message-input`) and show a disabled placeholder like "Join this room to send messages"
    - When `isMember` is `true`, render the message input fragment as before (no change for existing members)
    - Messages remain visible regardless of membership status
    - _Bug_Condition: Template had no membership awareness, all users saw the same UI_
    - _Expected_Behavior: Non-members see join banner + disabled input; members see normal chat UI_
    - _Preservation: Existing members see no change in UI_
    - _Requirements: 2.1, 2.2, 3.1, 3.5_

  - [x] 3.5 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Catalog Join Creates Membership
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior: after joining, `RoomMember` exists with `MEMBER` role
    - Note: The test from task 1 tests the GET flow (old bug path). After the fix, the catalog uses POST. Update the test to POST to `/rooms/{id}/join` and then GET `/chat/rooms/{id}` to verify membership and `isMember=true` in the model
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 3.6 Verify preservation tests still pass
    - **Property 2: Preservation** - Existing Member Room Access Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - Confirm all preservation tests still pass after fix (no regressions)

- [x] 4. Checkpoint - Ensure all tests pass
  - Run the full test suite to ensure no regressions
  - Verify bug condition test passes (membership created on catalog join)
  - Verify preservation tests pass (existing members, API join, ban enforcement, catalog browsing unchanged)
  - Ask the user if questions arise
