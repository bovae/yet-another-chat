# Bugfix Requirements Document

## Introduction

Users encounter `LazyInitializationException` errors in two scenarios: editing messages via the REST API (`PUT /api/rooms/{roomId}/messages/{id}`) and replying to messages via WebSocket STOMP (`/app/chat.send`). Both failures occur because Hibernate lazy proxies for `Message.replyTo` and `Message.replyTo.sender` are accessed outside of an active Hibernate session. The `open-in-view` setting is correctly disabled, so lazy loading only works within `@Transactional` boundaries.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a user edits a message that has a `replyTo` reference THEN the system throws `LazyInitializationException` and returns HTTP 500 because `MessageService.editMessage` uses `findByIdWithSender` which only fetches `sender` but not `replyTo` or `replyTo.sender`, and `MessageApiController.toResponse()` accesses these uninitialized proxies outside the transaction

1.2 WHEN a user sends a message via WebSocket with a `replyToId` THEN the system throws `LazyInitializationException` on the STOMP session because `ChatMessageHandler.sendMessage` fetches the reply-to message via `messageRepository.findById()` (standard JPA, no eager fetches) and then accesses `replyTo.getSender().getUsername()` outside the transaction boundary

1.3 WHEN a user edits a message that has a `replyTo` reference THEN the system does not fetch `replyTo.sender`, causing `toResponse()` to fail when accessing `replyTo.getSender().getUsername()` and `replyTo.getContent()`

### Expected Behavior (Correct)

2.1 WHEN a user edits a message that has a `replyTo` reference THEN the system SHALL return a successful response with the full message representation including `replyToSenderUsername` and `replyToContentSnippet` without throwing any lazy initialization errors

2.2 WHEN a user sends a message via WebSocket with a `replyToId` THEN the system SHALL broadcast the message with the correct `replyToSenderUsername` and `replyToContentSnippet` without throwing any lazy initialization errors

2.3 WHEN a user edits a message that has a `replyTo` reference THEN the system SHALL have `replyTo` and `replyTo.sender` fully initialized within the transactional boundary before the data is accessed in the controller/handler layer

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a user edits a message that has no `replyTo` reference THEN the system SHALL CONTINUE TO return a successful response with `replyToId`, `replyToSenderUsername`, and `replyToContentSnippet` as null

3.2 WHEN a user sends a message via WebSocket without a `replyToId` THEN the system SHALL CONTINUE TO broadcast the message with null reply-to fields

3.3 WHEN message history is fetched via `GET /api/rooms/{roomId}/messages` THEN the system SHALL CONTINUE TO return messages with fully populated reply-to data using the existing `findByRoomAndWatermarkGreaterThanWithFetches` query (which already joins `replyTo` and `replyTo.sender`)

3.4 WHEN a user sends a message via the REST API (`POST /api/rooms/{roomId}/messages`) with a `replyToId` THEN the system SHALL CONTINUE TO return the message with correct reply-to data (this path already uses `findByIdWithSender` for the replyTo lookup and passes the entity directly)

3.5 WHEN a user deletes a message THEN the system SHALL CONTINUE TO delete the message without errors regardless of whether it has a `replyTo` reference

---

## Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type MessageOperation
  OUTPUT: boolean

  // Returns true when the operation accesses replyTo or replyTo.sender
  // outside a Hibernate session after fetching with an incomplete query
  RETURN (X.operation = "editMessage" AND X.message.replyTo != null)
      OR (X.operation = "sendMessageWebSocket" AND X.replyToId != null)
END FUNCTION
```

## Fix Checking Property

```pascal
// Property: Fix Checking - Lazy proxy access resolved
FOR ALL X WHERE isBugCondition(X) DO
  result ← executeOperation'(X)
  ASSERT no_exception(result, "LazyInitializationException")
    AND result.replyToSenderUsername != null
    AND result.replyToContentSnippet != null
END FOR
```

## Preservation Checking Property

```pascal
// Property: Preservation Checking - Non-reply operations unchanged
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT F(X) = F'(X)
END FOR
```
