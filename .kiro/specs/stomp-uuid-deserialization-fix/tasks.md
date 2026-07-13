# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - STOMP Snake_Case UUID Deserialization Failure
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Use jqwik to generate random valid UUIDs and non-blank content strings, construct snake_case JSON payloads (`room_id`, `content`, optionally `reply_to_id`), and attempt deserialization via a `MappingJackson2MessageConverter` using a **default ObjectMapper** (no SNAKE_CASE strategy)
  - **Bug Condition**: `isBugCondition(input)` where `payload.hasField("room_id") OR payload.hasField("reply_to_id") AND objectMapper.namingStrategy != SNAKE_CASE`
  - **Test assertions** (encode Expected Behavior): The converter should deserialize the snake_case payload into a `ChatMessageRequest` where `roomId` matches the input UUID, `content` matches the input string, and `replyToId` matches the optional input UUID — these assertions will FAIL on unfixed code because the default ObjectMapper cannot map `room_id` → `roomId`
  - Create test file: `src/test/java/com/bovae/yac/property/StompUuidBugConditionPropertyTest.java`
  - Use `MappingJackson2MessageConverter` directly (unit-level, no Spring context needed)
  - Construct a `Message<byte[]>` with JSON bytes and appropriate content-type header
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS with `MessageConversionException` wrapping a Jackson deserialization error — this proves the STOMP converter's default ObjectMapper cannot handle snake_case fields
  - Document counterexamples found (e.g., `{"room_id": "550e8400-...", "content": "hello"}` throws deserialization error instead of producing a valid `ChatMessageRequest`)
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - REST API and STOMP ObjectMapper Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - **Observe**: On UNFIXED code, a `MappingJackson2MessageConverter` configured with Spring Boot's SNAKE_CASE `ObjectMapper` correctly round-trips `ChatMessageRequest` through serialization and deserialization — this is the REST-layer behavior we must preserve
  - **Observe**: On UNFIXED code, the SNAKE_CASE `ObjectMapper` serializes `ChatMessageRequest` fields to snake_case JSON (`room_id`, `content`, `reply_to_id`) and deserializes them back to matching Java record fields
  - Write jqwik property-based tests in `src/test/java/com/bovae/yac/property/StompUuidPreservationPropertyTest.java`:
    - **Property 2a**: For all randomly generated `ChatMessageRequest` values (random UUIDs, random non-blank content, optional random replyToId), serializing with the SNAKE_CASE `ObjectMapper` and deserializing back produces an equal `ChatMessageRequest` (round-trip preservation)
    - **Property 2b**: For all randomly generated `ChatMessageRequest` values, the SNAKE_CASE `ObjectMapper` serialization output contains snake_case keys (`room_id`, `content`, `reply_to_id`) and never camelCase keys (`roomId`, `replyToId`)
  - Verify tests pass on UNFIXED code — these capture baseline behavior that must not regress
  - **EXPECTED OUTCOME**: Tests PASS (confirms baseline REST/Jackson behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.4_

- [x] 3. Fix STOMP message converter to use Spring Boot's auto-configured ObjectMapper

  - [x] 3.1 Implement the fix in WebSocketConfig.java
    - Inject `ObjectMapper` as a `private final` field on `WebSocketConfig` — Lombok `@RequiredArgsConstructor` handles constructor injection of the auto-configured bean with SNAKE_CASE strategy
    - Override `configureMessageConverters(List<MessageConverter> messageConverters)` from `WebSocketMessageBrokerConfigurer`
    - Create a `MappingJackson2MessageConverter`, call `setObjectMapper(objectMapper)` with the injected instance
    - Add the converter to the `messageConverters` list and return `false` so default converters are still registered
    - No changes to `ChatMessageRequest`, `application.yml`, or other handlers
    - _Bug_Condition: isBugCondition(input) where payload has snake_case fields (room_id, reply_to_id) AND STOMP objectMapper.namingStrategy != SNAKE_CASE_
    - _Expected_Behavior: STOMP converter deserializes snake_case JSON into ChatMessageRequest with correct UUID roomId, String content, and optional UUID replyToId_
    - _Preservation: REST API serialization/deserialization unchanged; STOMP broadcasting unchanged; validation error handling unchanged_
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 3.1, 3.2, 3.3, 3.4_

  - [x] 3.2 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - STOMP Snake_Case UUID Deserialization Succeeds
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - Update the test in `StompUuidBugConditionPropertyTest.java` to use a SNAKE_CASE-configured `ObjectMapper` (matching the now-fixed STOMP converter behavior)
    - The test from task 1 encodes the expected behavior: snake_case payloads deserialize into correct `ChatMessageRequest` instances
    - When this test passes, it confirms the expected behavior is satisfied
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed — STOMP converter now uses SNAKE_CASE ObjectMapper)
    - _Requirements: 2.1, 2.2_

  - [x] 3.3 Verify preservation tests still pass
    - **Property 2: Preservation** - REST API and STOMP ObjectMapper Behavior Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions — REST API Jackson behavior unchanged)
    - Confirm all preservation tests still pass after fix

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the full test suite (`./mvnw test`) to verify no regressions
  - Ensure `StompUuidBugConditionPropertyTest` passes (bug is fixed)
  - Ensure `StompUuidPreservationPropertyTest` passes (no regressions)
  - Ensure all existing tests pass (no side effects from the WebSocketConfig change)
  - Ask the user if questions arise
