# STOMP UUID Deserialization Fix — Bugfix Design

## Overview

The STOMP WebSocket message converter uses a default `ObjectMapper` that does not inherit Spring Boot's auto-configured Jackson settings. The global `spring.jackson.property-naming-strategy: SNAKE_CASE` configuration only applies to the `ObjectMapper` bean managed by Spring Boot auto-configuration, which is used by Spring MVC's HTTP message converters. The STOMP messaging infrastructure creates its own `MappingJackson2MessageConverter` with a plain `ObjectMapper`, so snake_case fields sent by the JS client (`room_id`, `reply_to_id`) cannot be mapped to the camelCase Java record fields (`roomId`, `replyToId`), causing a UUID deserialization error.

The fix injects Spring Boot's auto-configured `ObjectMapper` into the WebSocket message converter configuration so that STOMP message deserialization respects the same SNAKE_CASE naming strategy as the REST layer.

## Glossary

- **Bug_Condition (C)**: A STOMP message payload contains snake_case field names (`room_id`, `reply_to_id`) that hold UUID string values, and the STOMP message converter's `ObjectMapper` lacks the SNAKE_CASE naming strategy, causing deserialization failure.
- **Property (P)**: The STOMP message converter correctly deserializes snake_case JSON fields into the corresponding camelCase Java record fields, including UUID types.
- **Preservation**: REST API deserialization/serialization, STOMP message broadcasting, and validation error handling must remain unchanged.
- **ChatMessageRequest**: The Java record in `com.bovae.yac.model.dto` with fields `roomId` (UUID, required), `content` (String, required), and `replyToId` (UUID, optional).
- **MappingJackson2MessageConverter**: The Spring Messaging converter that serializes/deserializes STOMP message payloads using Jackson's `ObjectMapper`.
- **SNAKE_CASE naming strategy**: Jackson's `PropertyNamingStrategies.SnakeCaseStrategy` that maps camelCase Java fields to/from snake_case JSON keys.

## Bug Details

### Bug Condition

The bug manifests when the JS STOMP client publishes a message to `/app/chat.send` with a JSON payload using snake_case field names. The `MappingJackson2MessageConverter` created by the STOMP infrastructure uses a default `ObjectMapper` without the SNAKE_CASE naming strategy. When Jackson attempts to deserialize `{"room_id": "...", "content": "..."}`, it cannot find a property named `room_id` on the `ChatMessageRequest` record (which has `roomId`), so it tries to interpret the nested object structure of the UUID string as an Object, producing: "Cannot deserialize value of type `java.util.UUID` from Object value".

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type StompMessage with JSON payload
  OUTPUT: boolean

  payload := parseJsonFields(input.body)
  RETURN payload.hasField("room_id") OR payload.hasField("reply_to_id")
         AND targetType = ChatMessageRequest
         AND objectMapper.namingStrategy != SNAKE_CASE
END FUNCTION
```

### Examples

- **Example 1**: Client sends `{"room_id": "550e8400-e29b-41d4-a716-446655440000", "content": "hello"}` → Server throws "Cannot deserialize value of type `java.util.UUID` from Object value" because `room_id` is not recognized as `roomId`.
- **Example 2**: Client sends `{"room_id": "550e8400-e29b-41d4-a716-446655440000", "content": "hello", "reply_to_id": "660e8400-e29b-41d4-a716-446655440000"}` → Same deserialization failure on `room_id` (and would also fail on `reply_to_id`).
- **Example 3**: Client sends `{"roomId": "550e8400-e29b-41d4-a716-446655440000", "content": "hello"}` using camelCase → This would actually work with the default ObjectMapper, but violates the application's convention of using snake_case on the wire.
- **Edge case**: Client sends `{"room_id": "550e8400-e29b-41d4-a716-446655440000", "content": "hello", "reply_to_id": null}` → Fails on `room_id` before even reaching `reply_to_id`.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- REST API endpoints must continue to deserialize snake_case JSON request bodies correctly using the globally configured `ObjectMapper`.
- REST API responses must continue to serialize Java objects to snake_case JSON.
- STOMP message broadcasting via `SimpMessagingTemplate.convertAndSend()` must continue to work, delivering messages to subscribed clients.
- Jakarta Bean Validation on `ChatMessageRequest` (e.g., `@NotNull roomId`, `@NotBlank content`, `@MaxByteSize(3072) content`) must continue to reject invalid payloads and route errors to the user's `/queue/errors`.
- Other STOMP handlers (`PresenceHandler`, `TypingHandler`) must continue to function correctly.

**Scope:**
All inputs that do NOT flow through the STOMP message converter's JSON deserialization path should be completely unaffected by this fix. This includes:
- HTTP REST requests (use Spring MVC's `MappingJackson2HttpMessageConverter`, already configured)
- STOMP CONNECT/DISCONNECT frames (no JSON payload)
- STOMP SUBSCRIBE/UNSUBSCRIBE frames (no JSON payload)
- Thymeleaf template rendering

## Hypothesized Root Cause

Based on the bug description and code analysis, the root cause is:

1. **Separate ObjectMapper instances**: Spring Boot auto-configures an `ObjectMapper` bean with the `SNAKE_CASE` naming strategy (from `spring.jackson.property-naming-strategy` in `application.yml`). Spring MVC's HTTP message converters use this bean. However, the STOMP messaging infrastructure creates its own `MappingJackson2MessageConverter` with a **new, default** `ObjectMapper` that has no naming strategy configured.

2. **WebSocketConfig does not customize message converters**: The current `WebSocketConfig` implements `WebSocketMessageBrokerConfigurer` but does not override `configureMessageConverters()`. This means the STOMP infrastructure falls back to its default converter setup, which does not consult Spring Boot's `ObjectMapper` bean.

3. **Field name mismatch causes type confusion**: When the default `ObjectMapper` encounters `room_id` in the JSON, it does not recognize it as the `roomId` field. Jackson's error message ("Cannot deserialize value of type `java.util.UUID` from Object value") indicates it is misinterpreting the JSON structure rather than simply reporting an unknown property, because the record constructor requires all fields.

## Correctness Properties

Property 1: Bug Condition — STOMP snake_case UUID fields deserialize correctly

_For any_ STOMP message payload containing snake_case field names (`room_id`, `reply_to_id`) with valid UUID string values and a non-blank `content` field, the fixed STOMP message converter SHALL correctly deserialize the payload into a `ChatMessageRequest` record where `roomId` and `replyToId` contain the corresponding UUID values.

**Validates: Requirements 2.1, 2.2**

Property 2: Preservation — REST API Jackson behavior unchanged

_For any_ REST API request or response that uses the globally configured `ObjectMapper`, the fixed code SHALL produce the same serialization and deserialization behavior as the original code, preserving snake_case naming strategy for all HTTP endpoints.

**Validates: Requirements 3.1, 3.4**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `src/main/java/com/bovae/yac/config/WebSocketConfig.java`

**Function**: Override `configureMessageConverters(List<MessageConverter> messageConverters)`

**Specific Changes**:

1. **Inject the auto-configured ObjectMapper**: Add a `private final ObjectMapper objectMapper` field to `WebSocketConfig`. Since the class already uses `@RequiredArgsConstructor`, Spring will inject the auto-configured `ObjectMapper` bean (which has the SNAKE_CASE naming strategy) via constructor injection.

2. **Override `configureMessageConverters`**: Implement the `configureMessageConverters` method from `WebSocketMessageBrokerConfigurer`. Create a `MappingJackson2MessageConverter`, set the injected `ObjectMapper` on it, and add it to the converters list. Return `false` from the method to indicate that default converters should still be added (preserving any other converter behavior).

3. **No changes to ChatMessageRequest**: The record does not need `@JsonNaming` or `@JsonProperty` annotations because the fix ensures the STOMP ObjectMapper already has the SNAKE_CASE strategy globally. Adding annotations would be redundant and inconsistent with the application's approach of using global configuration.

4. **No changes to application.yml**: The global Jackson configuration is already correct. The issue is that the STOMP layer doesn't use it.

5. **No changes to other handlers**: `PresenceHandler` and `TypingHandler` will automatically benefit from the same fix since they share the STOMP message converter infrastructure.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write a unit test that directly instantiates a `MappingJackson2MessageConverter` with a default `ObjectMapper` (simulating the current STOMP behavior) and attempts to deserialize a snake_case JSON payload into `ChatMessageRequest`. Run on UNFIXED code to observe the deserialization failure.

**Test Cases**:
1. **Default ObjectMapper Test**: Deserialize `{"room_id": "<uuid>", "content": "hello"}` using a default `ObjectMapper` — will fail on unfixed code, confirming the root cause.
2. **Optional reply_to_id Test**: Deserialize `{"room_id": "<uuid>", "content": "hello", "reply_to_id": "<uuid>"}` using a default `ObjectMapper` — will fail on unfixed code.
3. **SNAKE_CASE ObjectMapper Test**: Deserialize the same payloads using an `ObjectMapper` configured with SNAKE_CASE — will succeed, confirming the fix approach.

**Expected Counterexamples**:
- `MappingJackson2MessageConverter` with default `ObjectMapper` throws `MessageConversionException` wrapping a Jackson deserialization error when encountering `room_id`.
- Possible causes confirmed: the STOMP converter's ObjectMapper lacks the SNAKE_CASE naming strategy.

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  converter := MappingJackson2MessageConverter(snakeCaseObjectMapper)
  result := converter.fromMessage(input, ChatMessageRequest.class)
  ASSERT result.roomId() == expectedUUID(input)
  ASSERT result.content() == expectedContent(input)
  ASSERT result.replyToId() == expectedReplyToId(input)
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT restController.deserialize(input) = restController_original.deserialize(input)
  ASSERT restController.serialize(output) = restController_original.serialize(output)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many random UUID and content combinations to verify deserialization consistency
- It catches edge cases in UUID formatting or content encoding that manual tests might miss
- It provides strong guarantees that REST API behavior is unchanged

**Test Plan**: Observe behavior on UNFIXED code first for REST API requests, then write property-based tests capturing that behavior.

**Test Cases**:
1. **REST Deserialization Preservation**: Verify that REST API endpoints continue to deserialize snake_case JSON bodies correctly after the WebSocket converter change.
2. **REST Serialization Preservation**: Verify that REST API responses continue to produce snake_case JSON after the fix.
3. **STOMP Broadcast Preservation**: Verify that `SimpMessagingTemplate.convertAndSend()` continues to serialize outbound messages correctly.

### Unit Tests

- Test `MappingJackson2MessageConverter` with the injected SNAKE_CASE `ObjectMapper` deserializes `ChatMessageRequest` from snake_case JSON.
- Test edge cases: null `reply_to_id`, missing optional fields, boundary UUID values.
- Test that validation annotations still reject invalid payloads (blank content, null room_id).

### Property-Based Tests

- Generate random valid UUIDs and content strings, construct snake_case JSON payloads, and verify the STOMP converter produces correct `ChatMessageRequest` instances (fix checking).
- Generate random `ChatMessageRequest` instances, serialize via the SNAKE_CASE `ObjectMapper`, and verify round-trip deserialization produces the original values (preservation checking).
- Generate random REST request payloads and verify the HTTP message converter behavior is unchanged.

### Integration Tests

- Test the full STOMP message flow: connect → send snake_case message → verify message is broadcast to room topic with correct data.
- Test that `ChatMessageHandler.sendMessage()` correctly processes a deserialized `ChatMessageRequest` end-to-end.
- Test that validation errors on STOMP messages still route to the user's `/queue/errors` destination.
