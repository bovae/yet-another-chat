# Implementation Plan: UI/UX Polish Round 4

## Overview

Seven localized UI/UX improvements and one real-time bug fix for the YAC chat application. Requirements 1–6 are frontend-only changes (Thymeleaf templates + CSS). Requirement 7 adds a backend WebSocket broadcast for message deletion plus a client-side handler. All changes are incremental and independent — each task can be validated in isolation.

## Tasks

- [x] 1. Remove room description from chat header
  - [x] 1.1 Remove the `<small>` element displaying `room.description` from the `.chat-header` div in `src/main/resources/templates/chat/room.html`
    - The `<h6>` room name and the member count badge remain unchanged
    - _Requirements: 1.1, 1.2_
  - [x] 1.2 Remove the header description update from `submitRoomSettings` in `src/main/resources/static/js/app.js`
    - Remove the lines that find and update `headerDiv.querySelector('small')` with `updated.description`
    - The right panel description update (in `fragments/member-list.html`) must remain untouched
    - _Requirements: 1.2, 1.3_

- [x] 2. Remove hover highlight on chat messages
  - [x] 2.1 Remove the `.message-item:hover` CSS rule from `src/main/resources/static/css/chat.css`
    - Delete the rule block: `.message-item:hover { background: #f0f2f5; border-radius: 0.375rem; }`
    - Verify the `.message-bubble:hover .message-actions { opacity: 1; }` rule remains intact
    - _Requirements: 2.1, 2.2_

- [x] 3. Separate header section for sidebar action buttons
  - [x] 3.1 Restructure the bottom of `src/main/resources/templates/fragments/sidebar.html` to wrap the "Create room" and "Browse" buttons in a labeled section
    - Add `<hr>` separator after the Contacts section
    - Add `<h6 class="text-uppercase text-muted small">Actions</h6>` header label
    - Move the existing `<div class="d-flex gap-2">` with the two buttons under this new header
    - _Requirements: 3.1, 3.2, 3.3_

- [x] 4. Expand all sidebar accordion sections by default
  - [x] 4.1 Update the Private Rooms and Direct Messages accordion items in `src/main/resources/templates/fragments/sidebar.html`
    - Remove `collapsed` from the `<button>` class on Private Rooms and Direct Messages
    - Change `aria-expanded="false"` to `aria-expanded="true"` on both buttons
    - Add `show` to the `<div>` collapse class (change `class="accordion-collapse collapse"` to `class="accordion-collapse collapse show"`) for both sections
    - Public Rooms already has the correct expanded state — no changes needed there
    - _Requirements: 4.1, 4.2, 4.3_

- [x] 5. Checkpoint — Verify template and CSS changes
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Larger message input area
  - [x] 6.1 Update the textarea in `src/main/resources/templates/fragments/message-input.html`
    - Change `rows="1"` to `rows="2"` on the `<textarea>` element
    - _Requirements: 5.1_
  - [x] 6.2 Increase container padding in `src/main/resources/templates/fragments/message-input.html`
    - Change the outer div class from `p-2` to `p-3`
    - _Requirements: 5.2_

- [x] 7. Add "More" menu for non-owner members in left sidebar
  - [x] 7.1 Add a conditionally rendered "More" dropdown in `src/main/resources/templates/fragments/sidebar.html`
    - Place it below the new Actions section
    - Use `th:if="${currentUserRole != null and currentUserRole.name() == 'MEMBER'}"` to conditionally render
    - Include a Bootstrap dropdown with "Block user" and "Add friend" options
    - Wire the options to the existing `blockUser()` and `addFriend()` global functions in `app.js`
    - The dropdown must NOT appear for OWNER or ADMIN roles
    - _Requirements: 6.1, 6.2, 6.3_

- [x] 8. Create `MessageDeletedEvent` DTO
  - [x] 8.1 Create `src/main/java/com/bovae/yac/model/dto/MessageDeletedEvent.java` as a Java record
    - Fields: `String type`, `UUID messageId`, `UUID roomId`, `UUID deletedBy`
    - Add `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` for snake_case JSON serialization
    - Add a static factory method `of(UUID messageId, UUID roomId, UUID deletedBy)` that sets `type` to `"MESSAGE_DELETED"`
    - _Requirements: 7.2_

- [x] 9. Broadcast deletion event from `MessageApiController`
  - [x] 9.1 Inject `SimpMessagingTemplate` into `MessageApiController` via constructor (Lombok `@RequiredArgsConstructor`)
    - Add `private final SimpMessagingTemplate messagingTemplate;` field
    - _Requirements: 7.2_
  - [x] 9.2 After the `messageService.deleteMessage()` call in the `deleteMessage` method, broadcast the event
    - Create a `MessageDeletedEvent.of(id, roomId, user.getId())` and send it to `/topic/room.{roomId}` using `messagingTemplate.convertAndSend()`
    - This must happen after the service call succeeds but before returning the 204 response
    - _Requirements: 7.2_

- [x] 10. Handle deletion events in the STOMP client
  - [x] 10.1 Update the `handleIncomingMessage` function in `src/main/resources/static/js/stomp-client.js`
    - Check if the incoming message has `type === 'MESSAGE_DELETED'`
    - If so, delegate to a new `window.YAC.app.onMessageDeleted` handler instead of `onNewMessage`
    - Skip watermark tracking for deletion events (they have no watermark)
    - _Requirements: 7.3_
  - [x] 10.2 Add the `onMessageDeleted` handler in `src/main/resources/static/js/app.js`
    - Find the `.message-item[data-message-id="..."]` element matching `event.message_id` and remove it from the DOM
    - Update any `.reply-quote` elements in messages that reference the deleted message ID (set text to "Original message deleted")
    - Expose the handler via `window.YAC.app.onMessageDeleted`
    - _Requirements: 7.2, 7.3_

- [x] 11. Checkpoint — Verify all changes compile and tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Write unit tests for message deletion broadcast
  - [x] 12.1 Write a unit test for `MessageDeletedEvent.of()` factory method
    - Verify the `type` field is `"MESSAGE_DELETED"` and all UUID fields are correctly set
    - _Requirements: 7.2_
  - [x] 12.2 Write a unit test for `MessageApiController.deleteMessage()` verifying WebSocket broadcast
    - Use MockMvc with a mocked `SimpMessagingTemplate`
    - Verify `convertAndSend` is called with the correct destination `/topic/room.{roomId}` and a `MessageDeletedEvent` payload
    - Verify the response is still HTTP 204
    - _Requirements: 7.2, 7.3_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Requirements 1–6 are purely frontend (template + CSS) — no backend changes needed
- Requirement 7 is the only change requiring backend modification (DTO + controller)
- The design explicitly states property-based testing is not applicable for these changes
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation after frontend and backend changes
