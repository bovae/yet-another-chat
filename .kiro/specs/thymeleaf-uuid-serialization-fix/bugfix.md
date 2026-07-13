# Bugfix Requirements Document

## Introduction

Sending a chat message via STOMP WebSocket fails with a UUID deserialization error. The Thymeleaf template `room.html` renders `java.util.UUID` values as JavaScript objects (`{"mostSignificantBits": ..., "leastSignificantBits": ...}`) instead of plain UUID strings. This happens because Thymeleaf 3.1.3 was built for Jackson 2.x, but the project uses Jackson 3.x (`tools.jackson` namespace). Without Jackson 2.x on the classpath, Thymeleaf's JavaScript inline serializer falls back to its built-in object serializer for UUID types. The JS client then sends this object as `room_id`, and Jackson on the server rejects it because it expects a UUID string.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the Thymeleaf template renders `[[${room.id}]]` in a `th:inline="javascript"` block AND the application uses Jackson 3.x (tools.jackson namespace) THEN the system serializes the UUID as a JavaScript object `{"mostSignificantBits": ..., "leastSignificantBits": ...}` instead of a quoted UUID string

1.2 WHEN the JS client sends a STOMP message with the Thymeleaf-rendered `room_id` value THEN the server fails with `Cannot deserialize value of type java.util.UUID from Object value (token JsonToken.START_OBJECT)`

### Expected Behavior (Correct)

2.1 WHEN the Thymeleaf template renders `[[${room.id}]]` in a `th:inline="javascript"` block THEN the system SHALL produce a quoted UUID string (e.g., `"550e8400-e29b-41d4-a716-446655440000"`)

2.2 WHEN the JS client sends a STOMP message with the Thymeleaf-rendered `room_id` value THEN the server SHALL successfully deserialize the UUID and process the chat message

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the Thymeleaf template renders `[[${room.name}]]` in a `th:inline="javascript"` block THEN the system SHALL CONTINUE TO produce a properly quoted and escaped JavaScript string

3.2 WHEN the Thymeleaf template renders `[[${nextCursor}]]` and `[[${hasMore}]]` in a `th:inline="javascript"` block THEN the system SHALL CONTINUE TO produce correct JavaScript literal values (string and boolean respectively)

3.3 WHEN a STOMP message is sent with a valid UUID string as `room_id` THEN the server SHALL CONTINUE TO deserialize it and deliver the message to the room topic

---

## Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type ThymeleafInlineValue
  OUTPUT: boolean

  // Returns true when the inlined value is a java.util.UUID rendered
  // in a th:inline="javascript" block without explicit .toString()
  RETURN X.type = java.util.UUID
    AND X.renderContext = "th:inline javascript"
    AND X.serializationMethod = "Thymeleaf built-in" (Jackson 2.x absent)
END FUNCTION
```

## Property Specification

```pascal
// Property: Fix Checking — UUID values render as strings
FOR ALL X WHERE isBugCondition(X) DO
  rendered ← thymeleafInline(X.value.toString())
  ASSERT rendered IS quoted_string
    AND rendered MATCHES UUID_PATTERN "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
END FOR
```

## Preservation Goal

```pascal
// Property: Preservation Checking — non-UUID inlined values unchanged
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT thymeleafInline(X) = thymeleafInline'(X)
END FOR
```
