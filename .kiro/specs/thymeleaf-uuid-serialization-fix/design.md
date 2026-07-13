# Thymeleaf UUID Serialization Bugfix Design

## Overview

Sending a chat message via STOMP WebSocket fails because Thymeleaf 3.1.3's `th:inline="javascript"` block serializes `java.util.UUID` values as JavaBean objects (`{"mostSignificantBits": ..., "leastSignificantBits": ...}`) instead of plain UUID strings. This happens because Thymeleaf's `StandardJavaScriptSerializer` checks for Jackson 2's `com.fasterxml.jackson.databind.ObjectMapper` on the classpath, but Spring Boot 4.0.5 ships Jackson 3 (`tools.jackson` namespace). The class isn't found, so Thymeleaf falls back to `DefaultStandardJavaScriptSerializer`, which treats UUID as a JavaBean.

The fix is minimal: call `.toString()` on the UUID value in the Thymeleaf inline expression so it becomes a `String` before serialization. Strings are handled correctly by both the Jackson-based and fallback serializers.

## Glossary

- **Bug_Condition (C)**: A `java.util.UUID` value rendered via `[[${...}]]` inside a `th:inline="javascript"` block, where Thymeleaf's fallback serializer treats it as a JavaBean object instead of a string
- **Property (P)**: The UUID value SHALL render as a quoted string matching the standard UUID format (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`)
- **Preservation**: All non-UUID inlined values (`String`, `Long`, `boolean`) in the same `th:inline="javascript"` block must continue to render as correct JavaScript literals
- **`DefaultStandardJavaScriptSerializer`**: Thymeleaf's built-in fallback serializer used when Jackson 2 is not on the classpath; serializes objects by introspecting JavaBean properties
- **`StandardJavaScriptSerializer`**: Thymeleaf's JavaScript serializer that delegates to Jackson's `ObjectMapper` when available, falling back to `DefaultStandardJavaScriptSerializer`
- **`window.YAC_ROOM`**: The JavaScript object in `room.html` that holds server-rendered room metadata (`id`, `name`, `nextCursor`, `hasMore`) consumed by `app.js` and `stomp-client.js`

## Bug Details

### Bug Condition

The bug manifests when Thymeleaf renders `[[${room.id}]]` inside a `th:inline="javascript"` block. Because Jackson 2 (`com.fasterxml.jackson.databind.ObjectMapper`) is absent from the classpath (only Jackson 3's `tools.jackson.databind.json.JsonMapper` is present), Thymeleaf falls back to its built-in serializer which treats `java.util.UUID` as a JavaBean, emitting its `mostSignificantBits` and `leastSignificantBits` getter properties as a JavaScript object.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type ThymeleafInlineValue
  OUTPUT: boolean

  RETURN input.javaType = java.util.UUID
         AND input.renderContext = "th:inline javascript"
         AND NOT input.preConvertedToString
         AND jacksonTwoAbsentFromClasspath()
END FUNCTION
```

### Examples

- **Room page load**: `[[${room.id}]]` where `room.id` is `UUID("550e8400-e29b-41d4-a716-446655440000")` renders as `{"mostSignificantBits":6145390195186705876,"leastSignificantBits":-6384512774930063360}` instead of `"550e8400-e29b-41d4-a716-446655440000"`
- **Send message fails**: JS client calls `sendMessage(roomId, content)` where `roomId` is the object `{"mostSignificantBits":...}`, producing a STOMP payload `{"room_id":{"mostSignificantBits":...,"leastSignificantBits":...},"content":"hello"}`. Jackson 3 on the server sees `START_OBJECT` where it expects a string and throws `Cannot deserialize value of type java.util.UUID`
- **Room subscription works**: `stompClient.subscribe('/topic/room.' + roomId, ...)` concatenates the object, producing a garbled topic path like `/topic/room.[object Object]` — no messages are received
- **Edge case — null UUID**: If `room.id` were null, Thymeleaf renders `null` as a JavaScript literal, which is correct behavior and unaffected by this bug

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- `[[${room.name}]]` (String) must continue to render as a properly quoted and escaped JavaScript string
- `[[${nextCursor}]]` (Long) must continue to render as a JavaScript number literal
- `[[${hasMore}]]` (boolean) must continue to render as a JavaScript boolean literal (`true`/`false`)
- The `sendMessage(roomId, content, replyToId)` function in `stomp-client.js` must continue to work when given a valid UUID string
- The `data-room-id` HTML attribute on `#message-list` (rendered via `th:attr`) is unaffected — `th:attr` calls `.toString()` implicitly

**Scope:**
All inlined values that are NOT `java.util.UUID` are completely unaffected by this fix. The change is limited to adding `.toString()` on the single UUID expression in the template. No server-side Java code, JavaScript files, or other templates are modified.

## Hypothesized Root Cause

**Confirmed via bytecode inspection** — this is no longer a hypothesis:

1. **Jackson 2 class not found**: `StandardJavaScriptSerializer.computeJacksonPackageNameIfPresent()` does `ldc #1 // class com/fasterxml/jackson/databind/ObjectMapper`. Since only `tools.jackson.databind.json.JsonMapper` (Jackson 3) is on the classpath, the class resolution fails and the method returns `null`.

2. **Fallback serializer activated**: With no Jackson package detected, `StandardJavaScriptSerializer` delegates to `DefaultStandardJavaScriptSerializer` for all non-primitive, non-String, non-Date types.

3. **UUID treated as JavaBean**: `DefaultStandardJavaScriptSerializer` introspects `java.util.UUID` via `java.beans.Introspector`, discovers `getMostSignificantBits()` and `getLeastSignificantBits()` getters, and emits them as a JavaScript object with those two properties.

4. **String types unaffected**: `DefaultStandardJavaScriptSerializer` has explicit handling for `String`, `Number`, `Boolean`, and `Date` types — these are serialized correctly regardless of Jackson availability.

## Correctness Properties

Property 1: Bug Condition - UUID renders as quoted string

_For any_ `java.util.UUID` value rendered via `[[${uuid.toString()}]]` in a `th:inline="javascript"` block, the output SHALL be a quoted string matching the regex pattern `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`, and the resulting JavaScript variable SHALL hold a string (not an object).

**Validates: Requirements 2.1, 2.2**

Property 2: Preservation - Non-UUID inlined values unchanged

_For any_ inlined value in the same `th:inline="javascript"` block that is NOT a `java.util.UUID` (i.e., `String`, `Long`, `boolean`), the fixed template SHALL produce the same JavaScript output as the original template, preserving correct String quoting/escaping, numeric literals, and boolean literals.

**Validates: Requirements 3.1, 3.2, 3.3**

## Fix Implementation

### Changes Required

**File**: `src/main/resources/templates/chat/room.html`

**Block**: The `th:inline="javascript"` script block (lines 122–128)

**Specific Changes**:
1. **Add `.toString()` to `room.id`**: Change `[[${room.id}]]` to `[[${room.id.toString()}]]`. This converts the `UUID` to a `String` before Thymeleaf's serializer processes it. Since `DefaultStandardJavaScriptSerializer` handles `String` correctly (quoted and escaped), the output will be a proper UUID string literal.

**Before:**
```html
<script th:inline="javascript">
  window.YAC_ROOM = {
    id: [[${room.id}]],
    name: [[${room.name}]],
    nextCursor: [[${nextCursor}]],
    hasMore: [[${hasMore}]]
  };
</script>
```

**After:**
```html
<script th:inline="javascript">
  window.YAC_ROOM = {
    id: [[${room.id.toString()}]],
    name: [[${room.name}]],
    nextCursor: [[${nextCursor}]],
    hasMore: [[${hasMore}]]
  };
</script>
```

**No other files require changes.** The fix is a single expression change in one template. No Java code, JavaScript, or configuration changes are needed.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm the root cause analysis by observing the actual rendered output.

**Test Plan**: Write a test that renders `room.html` with a known UUID via Spring's `MockMvc` or Thymeleaf's `TemplateEngine` directly, then inspect the rendered HTML to observe how the UUID is serialized in the `window.YAC_ROOM` block. Run on the UNFIXED template to confirm the bug.

**Test Cases**:
1. **UUID Object Serialization Test**: Render `room.html` with a known `room.id` UUID and assert the output contains `mostSignificantBits` (will pass on unfixed code, confirming the bug)
2. **STOMP Payload Shape Test**: Simulate the JS client constructing a STOMP payload with the rendered `room_id` value and verify it produces an object instead of a string (will demonstrate the downstream failure)
3. **Topic Path Test**: Verify that string concatenation of the rendered UUID with `/topic/room.` produces a garbled path (will demonstrate subscription failure)

**Expected Counterexamples**:
- The rendered HTML contains `{"mostSignificantBits":...,"leastSignificantBits":...}` where a UUID string is expected
- Possible causes: confirmed — Thymeleaf fallback serializer treats UUID as JavaBean when Jackson 2 is absent

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed template produces the expected behavior.

**Pseudocode:**
```
FOR ALL uuid WHERE isBugCondition(uuid) DO
  rendered := thymeleafInline(uuid.toString())
  ASSERT rendered IS quoted_string
  ASSERT rendered MATCHES "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
  ASSERT typeof(JSON.parse(rendered)) = "string"
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed template produces the same result as the original template.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT renderOriginal(input) = renderFixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain (arbitrary strings, longs, booleans)
- It catches edge cases that manual unit tests might miss (e.g., strings with special characters, boundary long values)
- It provides strong guarantees that behavior is unchanged for all non-UUID inputs

**Test Plan**: Observe behavior on UNFIXED code first for `room.name` (String), `nextCursor` (Long), and `hasMore` (boolean), then write property-based tests capturing that behavior and verify it holds after the fix.

**Test Cases**:
1. **String Preservation**: Generate arbitrary strings (including special chars, unicode, empty) for `room.name` and verify the rendered JavaScript string is identical before and after the fix
2. **Long Preservation**: Generate arbitrary Long values for `nextCursor` and verify the rendered JavaScript number literal is identical before and after the fix
3. **Boolean Preservation**: Verify `hasMore` renders as `true` or `false` JavaScript literal identically before and after the fix

### Unit Tests

- Test that `UUID.toString()` produces a valid UUID string for arbitrary UUIDs (sanity check)
- Test that the rendered `window.YAC_ROOM.id` is a quoted string after the fix
- Test that `window.YAC_ROOM.name`, `window.YAC_ROOM.nextCursor`, and `window.YAC_ROOM.hasMore` are unchanged

### Property-Based Tests

- Generate random UUIDs via `@Provide` and verify `uuid.toString()` always matches the UUID regex pattern — validates that the `.toString()` approach is universally correct
- Generate random room names (strings with special characters, unicode, escaping edge cases) and verify Thymeleaf inline rendering produces valid JavaScript string literals — validates preservation
- Generate random `(Long, boolean)` pairs and verify Thymeleaf inline rendering produces correct JavaScript literals — validates preservation

### Integration Tests

- Render `room.html` via `MockMvc` with a test room and verify the `window.YAC_ROOM` block contains a proper UUID string
- Send a STOMP message with a UUID string `room_id` and verify the server successfully deserializes and processes it
- Verify the full flow: page load → extract `window.YAC_ROOM.id` → send STOMP message → message delivered to room topic
