# Message Lazy Initialization Fix — Bugfix Design

## Overview

When editing a message (REST PUT) or replying to a message (WebSocket STOMP), the system throws `LazyInitializationException` because the `Message.replyTo` and `Message.replyTo.sender` associations are not eagerly fetched within the transactional boundary. The fix introduces a new repository query that fetches `sender`, `replyTo`, and `replyTo.sender` in a single JOIN FETCH, and updates the two affected code paths to use it.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the bug — accessing `replyTo` or `replyTo.sender` lazy proxies outside a Hibernate session after fetching with an incomplete query
- **Property (P)**: The desired behavior — operations complete successfully with fully populated reply-to data (username, content snippet) without `LazyInitializationException`
- **Preservation**: Existing behavior for messages without `replyTo`, message history retrieval, message deletion, and REST POST send — all must remain unchanged
- **`findByIdWithSender`**: The existing repository query in `MessageRepository` that only JOIN FETCHes `m.sender` but not `m.replyTo` or `m.replyTo.sender`
- **`findByRoomAndWatermarkGreaterThanWithFetches`**: The existing query that correctly fetches `sender`, `replyTo`, and `replyTo.sender` — used for message history pagination
- **`open-in-view: false`**: Spring Boot configuration that disables the Open Session in View pattern, meaning lazy loading only works within `@Transactional` boundaries

## Bug Details

### Bug Condition

The bug manifests when a message with a non-null `replyTo` reference is loaded using a query that does not eagerly fetch `replyTo` and `replyTo.sender`, and then the response mapping layer accesses these lazy proxies outside the transaction boundary.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type MessageOperation
  OUTPUT: boolean

  RETURN (input.operation = "editMessage"
          AND input.message.replyTo != null
          AND queryUsed = "findByIdWithSender")
      OR (input.operation = "sendMessageWebSocket"
          AND input.replyToId != null
          AND queryUsed = "findById")
END FUNCTION
```

### Examples

- User edits message M1 which replies to message M2 → `editMessage` uses `findByIdWithSender` → controller calls `message.getReplyTo().getSender().getUsername()` → `LazyInitializationException` (replyTo proxy not initialized)
- User sends message via WebSocket with `replyToId=M2` → handler uses `messageRepository.findById(replyToId)` → accesses `replyTo.getSender().getUsername()` → `LazyInitializationException` (sender proxy on replyTo not initialized)
- User edits message M3 which has no `replyTo` → works fine because `replyTo` is null and the code skips the proxy access (not a bug condition)
- User sends message via WebSocket without `replyToId` → works fine because `replyTo` is null (not a bug condition)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Message history retrieval (`GET /api/rooms/{roomId}/messages`) must continue to use `findByRoomAndWatermarkGreaterThanWithFetches` and return fully populated reply-to data
- Editing a message without a `replyTo` must continue to return successfully with null reply-to fields
- Sending a message via WebSocket without a `replyToId` must continue to broadcast with null reply-to fields
- Sending a message via REST POST with a `replyToId` must continue to work (already uses `findByIdWithSender` for the replyTo lookup and passes the entity directly to `sendMessage`)
- Message deletion must continue to work regardless of `replyTo` presence
- Mouse/keyboard interactions, CSRF protection, and authentication flows are unaffected

**Scope:**
All operations that do NOT involve accessing `replyTo` or `replyTo.sender` on a message fetched with an incomplete query are completely unaffected by this fix.

## Hypothesized Root Cause

Based on the bug analysis, the root causes are:

1. **Incomplete JOIN FETCH in `findByIdWithSender`**: The query `SELECT m FROM Message m JOIN FETCH m.sender WHERE m.id = :id` only fetches `sender`. When `MessageService.editMessage` returns the message to `MessageApiController.toResponse()`, accessing `message.getReplyTo()` triggers a lazy load outside the closed session.

2. **No JOIN FETCH in `findById` (STOMP path)**: `ChatMessageHandler.sendMessage` uses the default `messageRepository.findById(replyToId)` which performs no eager fetching at all. Accessing `replyTo.getSender().getUsername()` immediately fails outside the transaction.

3. **Transaction boundary mismatch**: `MessageService.editMessage` is `@Transactional` but the response mapping in `MessageApiController.toResponse()` happens after the transaction commits. Similarly, `ChatMessageHandler.sendMessage` accesses lazy proxies after the `messageService.sendMessage` transaction completes.

4. **Inconsistency with existing working query**: The `findByRoomAndWatermarkGreaterThanWithFetches` query already correctly handles this with `LEFT JOIN FETCH m.replyTo rt LEFT JOIN FETCH rt.sender`, proving the pattern works — it was simply not applied to the single-message fetch queries.

## Correctness Properties

Property 1: Bug Condition - Lazy Proxies Fully Initialized

_For any_ message operation where the bug condition holds (editing a message with replyTo, or sending via WebSocket with replyToId), the fixed code SHALL complete without `LazyInitializationException` and return/broadcast a response containing non-null `replyToSenderUsername` and `replyToContentSnippet`.

**Validates: Requirements 2.1, 2.2, 2.3**

Property 2: Preservation - Non-ReplyTo Operations Unchanged

_For any_ message operation where the bug condition does NOT hold (editing without replyTo, sending without replyToId, message history, deletion), the fixed code SHALL produce exactly the same result as the original code, preserving all existing functionality for operations that do not involve accessing uninitialized replyTo proxies.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

## Fix Implementation

### Changes Required

**File**: `src/main/java/com/bovae/yac/repository/MessageRepository.java`

**New Query**: `findByIdWithSenderAndReplyTo`

**Specific Changes**:
1. **Add new repository method**: Add `findByIdWithSenderAndReplyTo` with JPQL that JOIN FETCHes `m.sender`, LEFT JOIN FETCHes `m.replyTo rt`, and LEFT JOIN FETCHes `rt.sender`. Uses LEFT JOIN for replyTo since it is nullable.

---

**File**: `src/main/java/com/bovae/yac/service/MessageService.java`

**Function**: `editMessage`

**Specific Changes**:
2. **Replace query call**: Change `messageRepository.findByIdWithSender(messageId)` to `messageRepository.findByIdWithSenderAndReplyTo(messageId)` so that the returned `Message` entity has `replyTo` and `replyTo.sender` fully initialized before leaving the transaction.

---

**File**: `src/main/java/com/bovae/yac/ws/ChatMessageHandler.java`

**Function**: `sendMessage`

**Specific Changes**:
3. **Replace query call**: Change `messageRepository.findById(request.replyToId())` to `messageRepository.findByIdWithSenderAndReplyTo(request.replyToId())` so that `replyTo.getSender().getUsername()` and `replyTo.getContent()` can be accessed safely outside the transaction.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write integration tests that perform edit and WebSocket send operations on messages with `replyTo` references. Run these tests on the UNFIXED code to observe `LazyInitializationException` failures and confirm the root cause.

**Test Cases**:
1. **Edit Message With ReplyTo (REST)**: Edit a message that has a `replyTo` reference via `PUT /api/rooms/{roomId}/messages/{id}` — expect `LazyInitializationException` on unfixed code
2. **Send Message With ReplyTo (WebSocket)**: Send a message via STOMP with a `replyToId` — expect `LazyInitializationException` on unfixed code
3. **Edit Message With Nested ReplyTo**: Edit a message whose `replyTo` itself has a `replyTo` — confirm only one level of replyTo needs fetching
4. **Send With Deleted ReplyTo Sender**: Send with a `replyToId` whose sender still exists — confirm the sender proxy is the issue

**Expected Counterexamples**:
- `LazyInitializationException` thrown when accessing `message.getReplyTo().getSender().getUsername()`
- Possible causes confirmed: `findByIdWithSender` missing `replyTo` fetch, `findById` missing all fetches

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := executeOperation_fixed(input)
  ASSERT no_exception(result, "LazyInitializationException")
    AND result.replyToSenderUsername != null
    AND result.replyToContentSnippet != null
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT executeOperation_original(input) = executeOperation_fixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain (various message states, null/non-null replyTo combinations)
- It catches edge cases that manual unit tests might miss (e.g., messages with null content in replyTo)
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for operations without replyTo (edits, sends, history), then write property-based tests capturing that behavior.

**Test Cases**:
1. **Edit Without ReplyTo Preservation**: Verify editing messages without `replyTo` continues to return successfully with null reply-to fields
2. **Send Without ReplyTo Preservation**: Verify WebSocket sends without `replyToId` continue to broadcast with null reply-to fields
3. **Message History Preservation**: Verify `GET /api/rooms/{roomId}/messages` continues to return fully populated data using the existing query
4. **Delete Preservation**: Verify message deletion continues to work regardless of `replyTo` presence

### Unit Tests

- Test `findByIdWithSenderAndReplyTo` returns a message with all associations initialized
- Test `findByIdWithSenderAndReplyTo` returns empty Optional for non-existent ID
- Test `findByIdWithSenderAndReplyTo` handles message with null `replyTo` (LEFT JOIN returns null)
- Test `editMessage` with replyTo does not throw `LazyInitializationException`
- Test `ChatMessageHandler.sendMessage` with replyToId does not throw `LazyInitializationException`

### Property-Based Tests

- Generate random message states (with/without replyTo, various content lengths) and verify the new query returns fully initialized entities
- Generate random edit operations on messages without replyTo and verify behavior matches original code
- Generate random WebSocket send operations without replyToId and verify broadcast matches original behavior

### Integration Tests

- Full REST flow: create message with replyTo, then edit it — verify 200 response with populated reply-to fields
- Full WebSocket flow: send message with replyToId via STOMP — verify broadcast contains correct replyToSenderUsername and replyToContentSnippet
- Mixed flow: send message via REST with replyTo, then edit via REST — verify both operations succeed
- Regression: verify message history endpoint still returns correct data after the fix
