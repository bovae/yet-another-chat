# Bugfix Requirements Document

## Introduction

This document covers 14 UX and logic bugs in the YAC (Yet Another Chat) application spanning backend lazy-loading issues, frontend/backend field name mismatches, missing UI behaviors, security gaps, and incorrect display logic. These bugs degrade user experience and in some cases expose security vulnerabilities.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a message is edited via REST API (`PUT /api/rooms/{roomId}/messages/{id}`) THEN the system throws `LazyInitializationException` because `MessageApiController.toResponse()` accesses `message.getSender().getUsername()` on a lazy-loaded User proxy after the Hibernate session is closed

1.2 WHEN a message that has replies is deleted live via WebSocket THEN the system removes the deleted message element from the DOM but reply quotes referencing it disappear entirely instead of showing "Original message deleted"

1.3 WHEN the user hovers anywhere on the message row (`.message-item`) THEN the system shows message action buttons (edit, delete, reply), even when the cursor is outside the message bubble

1.4 WHEN a user clicks on a room entry in the Public Room Catalog page THEN the system does nothing because room entries are not clickable links; additionally the "Join" button is always displayed even if the user is already a member of the room

1.5 WHEN a user views the Sessions page THEN the system only shows session creation time and last accessed time, without browser details (User-Agent) or IP address

1.6 WHEN a user submits the password change form on the profile page THEN the system returns validation errors "newPassword: не должно быть пустым, currentPassword: не должно быть пустым" because the frontend sends camelCase field names (`currentPassword`, `newPassword`) but the backend Jackson global snake_case naming strategy expects `current_password` and `new_password`

1.7 WHEN a user updates their display name on the profile page THEN the system does not persist the change because the frontend sends `{ "displayName": "..." }` but the backend expects `{ "display_name": "..." }` due to the global Jackson snake_case naming strategy

1.8 WHEN a user opens a direct message room THEN the system displays the internal room name (e.g., "dm-e5418c50-79307c3e") in the chat header and room info panel instead of a human-readable name like "Chat with <other_user_name>"

1.9 WHEN a user sends a message in a room THEN the system shows an unread notification badge for the sender themselves because the sender's `lastReadWatermark` is not updated upon sending, causing `computeUnreadCount` to return a positive value when the sidebar refreshes

1.10 WHEN any authenticated user navigates directly to a Saved Messages room URL belonging to another user THEN the system renders the room page and displays messages because `ChatWebController.roomView()` does not enforce membership-based access control on message content

1.11 WHEN a user types in the sidebar search input THEN the system does not filter the room/contact lists because `setupSearch()` looks for a `.sidebar` CSS class selector but the actual sidebar container uses a different class name (`chat-sidebar`)

1.12 WHEN a user views the contacts section in the sidebar THEN the system shows the user themselves in the list because `getCurrentUserId()` returns a string but the friendship API returns UUID strings that may not match due to type coercion issues in the equality comparison

1.13 WHEN a user is invited to a private room THEN the system does not display the invitation in the sidebar because the `RoomInvitationRepository.findByInvitee()` query does not eagerly fetch the associated `room` and `inviter` entities, causing serialization issues or empty responses

1.14 WHEN a user submits the password reset form (with token and new password) THEN the system returns a validation error because the frontend sends `{ "token": "...", "newPassword": "..." }` but the backend Jackson global snake_case naming strategy expects `new_password`; the `token` field works (single word, no case difference) but `newPassword` is not recognized, leaving it null and failing `@NotBlank` validation

### Expected Behavior (Correct)

2.1 WHEN a message is edited via REST API THEN the system SHALL return a valid `ChatMessageResponse` with the sender's username and ID populated correctly, without throwing `LazyInitializationException`

2.2 WHEN a message that has replies is deleted live via WebSocket THEN the system SHALL update all reply quotes referencing the deleted message to display "Original message deleted" text

2.3 WHEN the user hovers over the message bubble (`.message-bubble`) THEN the system SHALL show message action buttons; WHEN the cursor is outside the bubble but still on the message row THEN the system SHALL keep action buttons hidden

2.4 WHEN a user clicks on a room entry in the Public Room Catalog THEN the system SHALL navigate the user to that room's chat page; WHEN the user is already a member of a room THEN the system SHALL display a "Joined" indicator instead of the "Join" button

2.5 WHEN a user views the Sessions page THEN the system SHALL display browser details (parsed from User-Agent) and IP address for each active session, if available

2.6 WHEN a user submits the password change form with all fields filled THEN the system SHALL successfully change the password without validation errors, by ensuring frontend field names match the backend DTO expectations

2.7 WHEN a user updates their display name on the profile page THEN the system SHALL persist the change and reflect the updated name, by ensuring the frontend JSON field name matches the backend DTO expectation

2.8 WHEN a user opens a direct message room THEN the system SHALL display "Chat with <other_user_display_name_or_username>" in the chat header and room info panel instead of the internal room name

2.9 WHEN a user sends a message in a room THEN the system SHALL update the sender's `lastReadWatermark` to the new message's watermark so that no unread badge appears for the sender

2.10 WHEN any user attempts to access a room they are not a member of THEN the system SHALL deny access to message content and return an appropriate error or redirect, preventing unauthorized access to Saved Messages and other private rooms

2.11 WHEN a user types in the sidebar search input THEN the system SHALL filter the room and contact lists to show only items matching the search term

2.12 WHEN a user views the contacts section THEN the system SHALL NOT display the user themselves in the contacts list; only actual friends shall be shown

2.13 WHEN a user is invited to a private room THEN the system SHALL display the invitation in the sidebar with room name, inviter username, and accept/decline buttons

2.14 WHEN a user submits the password reset form with a valid token and new password THEN the system SHALL successfully reset the password, by ensuring the frontend sends `new_password` (snake_case) to match the backend DTO expectation

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a message is sent via WebSocket (`/app/chat.send`) THEN the system SHALL CONTINUE TO broadcast the message to all room members and return a valid response

3.2 WHEN a message is deleted and no other messages reference it as a reply THEN the system SHALL CONTINUE TO simply remove the message element from the DOM without affecting other messages

3.3 WHEN the user hovers over the message bubble THEN the system SHALL CONTINUE TO show action buttons with the same fade-in transition effect

3.4 WHEN a non-member views the Public Room Catalog THEN the system SHALL CONTINUE TO display the room list with search and pagination functionality

3.5 WHEN a user terminates a session from the Sessions page THEN the system SHALL CONTINUE TO invalidate that session successfully

3.6 WHEN a user submits the password reset form (forgot password flow) with correct snake_case field names THEN the system SHALL CONTINUE TO process the reset token and update the password correctly

3.7 WHEN a user's profile is loaded THEN the system SHALL CONTINUE TO display the current display name and username correctly

3.8 WHEN a user opens a Saved Messages room (self-DM) THEN the system SHALL CONTINUE TO display "Saved Messages" as the room name in the header

3.9 WHEN a user receives a message from another user THEN the system SHALL CONTINUE TO show the unread notification badge for the recipient

3.10 WHEN a user who IS a member of a room navigates to that room THEN the system SHALL CONTINUE TO render the room page with full message history and input controls

3.11 WHEN the sidebar initially loads THEN the system SHALL CONTINUE TO populate all room lists (public, private, direct) and contacts correctly

3.12 WHEN a user clicks on a contact in the sidebar THEN the system SHALL CONTINUE TO initiate a direct chat with that contact

3.13 WHEN a user accepts a room invitation THEN the system SHALL CONTINUE TO add them as a member and redirect to the room
