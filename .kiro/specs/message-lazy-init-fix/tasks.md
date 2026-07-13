# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - LazyInitializationException on Edit and WebSocket Reply
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate LazyInitializationException when accessing `replyTo`/`replyTo.sender` outside a Hibernate session
  - **Scoped PBT Approach**: Scope the property to concrete failing cases — editing a message that has a `replyTo` reference (REST PUT), and sending a WebSocket message with a `replyToId` (tested via repository-level proxy access)
  - Create test class `src/test/java/com/bovae/yac/property/MessageLazyInitBugConditionPropertyTest.java`
  - Use `@JqwikSpringSupport`, `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfig.class)`
  - Do NOT use `@Transactional` on the test class — the bug only manifests outside a transaction boundary
  - Use `@AfterTry` cleanup to delete attachments → nullify replyTo references → delete messages → delete room members → delete rooms → delete users (respecting FK constraints)
  - **Property 1a — REST edit with replyTo (Requirements 1.1, 1.3)**:
    - Set up: register user via `UserService.register`, create room via `RoomService.createRoom`, send original message via `MessageService.sendMessage`, send reply message with `replyTo` set
    - `PUT /api/rooms/{roomId}/messages/{replyMessageId}` with `{"content": "edited"}` using `MockMvc` with `user(email).roles("USER")` and `csrf()`
    - Assert HTTP 200 (not 500)
    - Assert response JSON `$.reply_to_sender_username` is non-null
    - Assert response JSON `$.reply_to_content_snippet` is non-null
    - Bug condition: `isBugCondition(X) = X.operation = "editMessage" AND X.message.replyTo != null`
    - On UNFIXED code: `findByIdWithSender` only fetches `sender`, not `replyTo`/`replyTo.sender` → `toResponse()` triggers `LazyInitializationException` → HTTP 500
    - Expected counterexample: "PUT edit of reply-message returns 500 instead of 200 with populated reply-to fields"
  - **Property 1b — Repository-level proxy access simulating WebSocket path (Requirement 1.2)**:
    - Set up: register user, create room, send original message, send reply message with `replyTo` set
    - Call `messageRepository.findById(replyMessageId)` outside `@Transactional` (simulates `ChatMessageHandler.sendMessage` fetch)
    - Access `message.getReplyTo().getSender().getUsername()` on the returned entity
    - Assert no `LazyInitializationException` is thrown and username is non-null
    - Bug condition: `isBugCondition(X) = X.operation = "sendMessageWebSocket" AND X.replyToId != null`
    - On UNFIXED code: `findById` fetches nothing eagerly → accessing `replyTo.getSender()` outside transaction throws `LazyInitializationException`
    - Note: MockMvc cannot test STOMP directly, so this tests the same root cause at the repository level
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct — it proves the bug exists)
  - Document counterexamples found to understand root cause
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Non-ReplyTo Operations Unchanged
  - **IMPORTANT**: Follow observation-first methodology — observe behavior on UNFIXED code first, then write property tests capturing that behavior
  - Create test class `src/test/java/com/bovae/yac/property/MessageLazyInitPreservationPropertyTest.java`
  - Use `@JqwikSpringSupport`, `@SpringBootTest`, `@AutoConfigureMockMvc`, `@Import(TestcontainersConfig.class)`
  - Do NOT use `@Transactional` on the test class — must match production behavior where controller/handler code runs outside the service transaction
  - Use `@AfterTry` cleanup to delete attachments → nullify replyTo references → delete messages → delete room members → delete rooms → delete users (respecting FK constraints)
  - **Observation phase** — run on UNFIXED code and record actual outputs:
    - Observe: editing a message with NO `replyTo` returns 200 with `reply_to_id`, `reply_to_sender_username`, `reply_to_content_snippet` all null
    - Observe: sending a message via REST POST without `reply_to_id` returns 201 with null reply-to fields
    - Observe: sending a message via REST POST with `reply_to_id` returns 201 with non-null reply-to fields (this path already works)
    - Observe: message history GET returns messages with fully populated reply-to data (existing `findByRoomAndWatermarkGreaterThanWithFetches` already joins correctly)
    - Observe: deleting a message returns 204 without errors
  - **Property 2a — Edit message without replyTo returns null reply-to fields (Requirement 3.1)**:
    - Use jqwik `@Property` with arbitrary content strings (alpha, 1–100 chars)
    - For all valid content strings, `PUT /api/rooms/{roomId}/messages/{id}` on a message with no `replyTo`
    - Assert HTTP 200 with `$.reply_to_id` = null, `$.reply_to_sender_username` = null, `$.reply_to_content_snippet` = null
    - Assert `$.content` matches the new content, `$.edited` = true
    - Non-bug condition: `NOT isBugCondition(X)` where `X.operation = "editMessage" AND X.message.replyTo = null`
  - **Property 2b — Send message via REST POST without replyToId returns null reply-to fields (Requirement 3.4)**:
    - Use jqwik `@Property` with arbitrary content strings
    - For all valid content strings, `POST /api/rooms/{roomId}/messages` with `{"room_id": "...", "content": "..."}` (no `reply_to_id`)
    - Assert HTTP 201 with non-null `$.sender_username` and null `$.reply_to_id`, `$.reply_to_sender_username`, `$.reply_to_content_snippet`
  - **Property 2c — Send message via REST POST with replyToId returns correct reply-to data (Requirement 3.4)**:
    - Create an original message, then send a reply via `POST /api/rooms/{roomId}/messages` with `reply_to_id` set
    - Assert HTTP 201 with non-null `$.reply_to_sender_username` and non-null `$.reply_to_content_snippet`
    - This path already works because `MessageApiController.sendMessage` uses `findByIdWithSender` for the replyTo lookup and passes the entity directly
  - **Property 2d — Message history returns fully populated reply-to data (Requirement 3.3)**:
    - Create a message with a reply, then fetch history via `GET /api/rooms/{roomId}/messages`
    - Assert response contains the reply message with non-null `$.messages[*].reply_to_sender_username` for messages that have replyTo
    - This path uses `findByRoomAndWatermarkGreaterThanWithFetches` which already joins `replyTo` and `replyTo.sender`
  - **Property 2e — Delete message succeeds regardless of replyTo (Requirement 3.5)**:
    - Create a message with a `replyTo`, then `DELETE /api/rooms/{roomId}/messages/{id}`
    - Assert HTTP 204
  - Verify all tests PASS on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 3. Fix for LazyInitializationException on message edit and WebSocket reply

  - [x] 3.1 Add `findByIdWithSenderAndReplyTo` query to `MessageRepository`
    - Open `src/main/java/com/bovae/yac/repository/MessageRepository.java`
    - Add new JPQL query method:
      ```java
      @Query("SELECT m FROM Message m JOIN FETCH m.sender LEFT JOIN FETCH m.replyTo rt LEFT JOIN FETCH rt.sender WHERE m.id = :id")
      Optional<Message> findByIdWithSenderAndReplyTo(@Param("id") UUID id);
      ```
    - Uses `JOIN FETCH m.sender` (sender is non-null, inner join is correct)
    - Uses `LEFT JOIN FETCH m.replyTo rt` (replyTo is nullable, must be LEFT JOIN)
    - Uses `LEFT JOIN FETCH rt.sender` (only fetched when replyTo exists)
    - Pattern matches existing `findByRoomAndWatermarkGreaterThanWithFetches` which already uses the same LEFT JOIN FETCH approach
    - _Bug_Condition: isBugCondition(X) where X.message.replyTo != null AND fetch query does not join replyTo/replyTo.sender_
    - _Expected_Behavior: New query fetches sender, replyTo, and replyTo.sender in a single query so no lazy proxy is accessed outside transaction_
    - _Preservation: Existing `findByIdWithSender` and `findByRoomAndWatermarkGreaterThanWithFetches` remain unchanged_
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 3.2 Update `MessageService.editMessage` to use `findByIdWithSenderAndReplyTo`
    - Open `src/main/java/com/bovae/yac/service/MessageService.java`
    - In `editMessage()` method, replace:
      `messageRepository.findByIdWithSender(messageId)` → `messageRepository.findByIdWithSenderAndReplyTo(messageId)`
    - This ensures `replyTo` and `replyTo.sender` are initialized within the `@Transactional` boundary
    - The returned `Message` entity will have all associations needed by `MessageApiController.toResponse()` which accesses `message.getReplyTo().getSender().getUsername()` and `replyTo.getContent()`
    - _Bug_Condition: editMessage uses findByIdWithSender which only fetches sender, not replyTo/replyTo.sender_
    - _Expected_Behavior: editMessage uses findByIdWithSenderAndReplyTo so replyTo and replyTo.sender are available outside transaction_
    - _Preservation: sendMessage, deleteMessage, getMessageHistory remain unchanged_
    - _Requirements: 2.1, 2.3_

  - [x] 3.3 Update `ChatMessageHandler.sendMessage` to use `findByIdWithSenderAndReplyTo`
    - Open `src/main/java/com/bovae/yac/ws/ChatMessageHandler.java`
    - In `sendMessage()` method, replace:
      `messageRepository.findById(request.replyToId())` → `messageRepository.findByIdWithSenderAndReplyTo(request.replyToId())`
    - This ensures the `replyTo` message has `sender` eagerly fetched so `replyTo.getSender().getUsername()` and `replyTo.getContent()` work outside the transaction
    - _Bug_Condition: STOMP handler uses findById which fetches nothing eagerly — all associations are lazy proxies_
    - _Expected_Behavior: STOMP handler uses findByIdWithSenderAndReplyTo so sender and content are available for response construction_
    - _Preservation: The rest of the STOMP handler logic (room lookup, broadcast, notifications) remains unchanged_
    - _Requirements: 2.2_

  - [x] 3.4 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - LazyInitializationException Resolved
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior (HTTP 200 with populated reply-to fields for REST edit, no exception for repository-level proxy access)
    - When this test passes, it confirms the expected behavior is satisfied
    - Run `MessageLazyInitBugConditionPropertyTest`
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 3.5 Verify preservation tests still pass
    - **Property 2: Preservation** - Non-ReplyTo Operations Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run `MessageLazyInitPreservationPropertyTest`
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)
    - Confirm all preservation tests still pass after fix (no regressions)

- [x] 4. Checkpoint - Ensure all tests pass
  - Run the full test suite: `./mvnw verify`
  - Ensure `MessageLazyInitBugConditionPropertyTest` passes (bug is fixed)
  - Ensure `MessageLazyInitPreservationPropertyTest` passes (no regressions)
  - Ensure all other existing tests still pass
  - Ask the user if questions arise
