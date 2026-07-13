# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Thymeleaf UUID Inline Serialization Bug
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists in `room.html`
  - **Scoped PBT Approach**: For any `java.util.UUID` value, render the `th:inline="javascript"` block from `room.html` using Thymeleaf's `SpringTemplateEngine` and verify the rendered `window.YAC_ROOM.id` is a quoted UUID string matching `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`
  - Create test class `src/test/java/com/bovae/yac/property/ThymeleafUuidBugConditionPropertyTest.java`
  - Use jqwik `@Property` with `@Provide` arbitrary for random UUIDs
  - Set up a minimal Thymeleaf `SpringTemplateEngine` with `ClassLoaderTemplateResolver` pointing to `templates/`
  - Create a Thymeleaf `Context` with model attributes: `room` (a `Room` entity with the generated UUID id and a test name), `nextCursor` (a Long), `hasMore` (a boolean)
  - Render `chat/room` template and extract the `window.YAC_ROOM` JavaScript block from the rendered HTML
  - Assert that the rendered `id` field is a quoted string matching the UUID regex pattern (not a `{"mostSignificantBits":...,"leastSignificantBits":...}` object)
  - Run test on UNFIXED code (`[[${room.id}]]` without `.toString()`)
  - **EXPECTED OUTCOME**: Test FAILS — the rendered output contains `mostSignificantBits`/`leastSignificantBits` instead of a UUID string, confirming the bug exists
  - Document counterexamples found (e.g., `UUID("550e8400-...") renders as {"mostSignificantBits":6145390195186705876,"leastSignificantBits":-6384512774930063360}`)
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 2.1_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Non-UUID Thymeleaf Inline Values Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Create test class `src/test/java/com/bovae/yac/property/ThymeleafUuidPreservationPropertyTest.java`
  - Use the same Thymeleaf `SpringTemplateEngine` setup as the bug condition test
  - **Observe on UNFIXED code**:
    - `[[${room.name}]]` (String) renders as a properly quoted and escaped JavaScript string
    - `[[${nextCursor}]]` (Long) renders as a JavaScript number literal
    - `[[${hasMore}]]` (boolean) renders as `true` or `false` JavaScript literal
  - **Property 2a — String preservation**: For all arbitrary strings (including special chars, unicode, quotes, backslashes), verify `room.name` renders as a valid JavaScript string literal in the `window.YAC_ROOM` block
  - **Property 2b — Long preservation**: For all arbitrary Long values (including 0, negatives, Long.MAX_VALUE, Long.MIN_VALUE), verify `nextCursor` renders as a correct JavaScript number literal
  - **Property 2c — Boolean preservation**: For both `true` and `false`, verify `hasMore` renders as the correct JavaScript boolean literal
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS — non-UUID values are serialized correctly by Thymeleaf's fallback serializer regardless of Jackson 2 absence
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2_

- [x] 3. Fix Thymeleaf UUID inline serialization in room.html

  - [x] 3.1 Implement the fix
    - In `src/main/resources/templates/chat/room.html`, change `[[${room.id}]]` to `[[${room.id.toString()}]]` in the `th:inline="javascript"` block (line ~124)
    - This converts the UUID to a String before Thymeleaf's serializer processes it, ensuring the fallback `DefaultStandardJavaScriptSerializer` handles it as a quoted string
    - No other files require changes — the fix is a single expression change
    - _Bug_Condition: isBugCondition(input) where input.javaType = java.util.UUID AND input.renderContext = "th:inline javascript" AND NOT input.preConvertedToString AND jacksonTwoAbsentFromClasspath()_
    - _Expected_Behavior: rendered UUID is a quoted string matching `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`_
    - _Preservation: Non-UUID inlined values (String room.name, Long nextCursor, boolean hasMore) produce identical JavaScript output_
    - _Requirements: 1.1, 1.2, 2.1, 2.2, 3.1, 3.2_

  - [x] 3.2 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Thymeleaf UUID Inline Serialization Fixed
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior (UUID renders as quoted string)
    - When this test passes, it confirms the UUID is now serialized as a proper string
    - Run `ThymeleafUuidBugConditionPropertyTest` from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed — UUID renders as `"550e8400-..."` not `{"mostSignificantBits":...}`)
    - _Requirements: 2.1, 2.2_

  - [x] 3.3 Verify preservation tests still pass
    - **Property 2: Preservation** - Non-UUID Thymeleaf Inline Values Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run `ThymeleafUuidPreservationPropertyTest` from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions — String, Long, boolean values render identically)
    - Confirm all preservation properties still hold after the `.toString()` change

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the full test suite to verify no regressions
  - Ensure `ThymeleafUuidBugConditionPropertyTest` passes (bug is fixed)
  - Ensure `ThymeleafUuidPreservationPropertyTest` passes (no regressions)
  - Ensure existing `StompUuidBugConditionPropertyTest` and `StompUuidPreservationPropertyTest` still pass
  - Ensure all other project tests pass
  - Ask the user if questions arise
