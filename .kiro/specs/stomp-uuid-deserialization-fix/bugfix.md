# Bugfix Requirements Document

## Introduction

When a user sends a chat message via the STOMP WebSocket client, the server fails to deserialize the incoming JSON payload. The JS client sends snake_case field names (`room_id`, `reply_to_id`) as expected by the application's global Jackson configuration (`spring.jackson.property-naming-strategy: SNAKE_CASE`). However, the STOMP WebSocket message converter uses its own default `ObjectMapper` that does not inherit Spring Boot's auto-configured Jackson settings. This causes the `room_id` field to fail mapping to the `roomId` Java record field, resulting in a UUID deserialization error that prevents all STOMP-based messaging.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a STOMP message is sent with snake_case JSON fields (e.g., `{"room_id": "uuid-string", "content": "hello"}`) THEN the system throws a JSON deserialization error: "Cannot deserialize value of type `java.util.UUID` from Object value" because the STOMP message converter's ObjectMapper does not apply the SNAKE_CASE naming strategy and cannot map `room_id` to the `roomId` record field.

1.2 WHEN a STOMP message includes an optional `reply_to_id` field in snake_case THEN the system fails to map it to the `replyToId` record field, causing the same deserialization failure.

### Expected Behavior (Correct)

2.1 WHEN a STOMP message is sent with snake_case JSON fields (e.g., `{"room_id": "uuid-string", "content": "hello"}`) THEN the system SHALL correctly deserialize the payload into a `ChatMessageRequest` by applying the SNAKE_CASE naming strategy, mapping `room_id` to `roomId` as a valid UUID.

2.2 WHEN a STOMP message includes an optional `reply_to_id` field in snake_case THEN the system SHALL correctly map it to the `replyToId` field and deserialize it as a UUID.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a REST API request is sent with snake_case JSON fields THEN the system SHALL CONTINUE TO deserialize the payload correctly using the globally configured SNAKE_CASE ObjectMapper.

3.2 WHEN a STOMP message is sent with valid content and a valid room ID THEN the system SHALL CONTINUE TO broadcast the message to the appropriate room topic.

3.3 WHEN a STOMP message fails validation (e.g., blank content, null room ID) THEN the system SHALL CONTINUE TO return an appropriate error to the user's error queue.

3.4 WHEN a REST API response is serialized THEN the system SHALL CONTINUE TO produce snake_case JSON field names.
