# Bugfix Requirements Document

## Introduction

This document addresses seven bugs in the YAC application that affect core messaging, presence, password reset, invitation, room display, and session management features. These bugs degrade the user experience by preventing message editing, causing WebSocket errors on message send, showing incorrect online status, failing to persist password resets, hiding room invitations from non-friend users, displaying internal UUIDs for the Saved Messages room, and crashing the sessions page due to a Thymeleaf reserved variable conflict.

## Bug Analysis

### Bug 1: Message Edit Fails with "roomId: must not be null"

#### Current Behavior (Defect)

1.1 WHEN a user edits a message via the REST API (`PUT /api/rooms/{roomId}/messages/{id}`) and the request body contains only `content` (without `roomId`) THEN the system returns a validation error "roomId: must not be null" because `ChatMessageRequest` requires `@NotNull UUID roomId`

1.2 WHEN the frontend sends an edit request with body `{ "content": "new text" }` THEN the system rejects the request with HTTP 400 despite `roomId` being available in the URL path

#### Expected Behavior (Correct)

2.1 WHEN a user edits a message via `PUT /api/rooms/{roomId}/messages/{id}` with a body containing only `content` THEN the system SHALL accept the request and update the message content without requiring `roomId` in the request body

2.2 WHEN a user edits a message via the REST API THEN the system SHALL use the `roomId` from the URL path variable and only require `content` in the request body

#### Unchanged Behavior (Regression Prevention)

3.1 WHEN a user sends a new message via WebSocket (`/app/chat.send`) with `roomId`, `content`, and optional `replyToId` in the payload THEN the system SHALL CONTINUE TO validate that `roomId` is present and route the message correctly

3.2 WHEN a user sends a new message via the REST API (`POST /api/rooms/{roomId}/messages`) THEN the system SHALL CONTINUE TO create the message in the specified room

---

### Bug 2: LazyInitializationException on Message Send via WebSocket

#### Current Behavior (Defect)

1.3 WHEN a user sends a message via WebSocket (`/app/chat.send`) THEN the system throws `LazyInitializationException: Could not initialize proxy [com.bovae.yac.model.entity.User#...] - no session` because the `ChatMessageHandler.sendMessage()` method is not transactional and accesses lazy-loaded entity associations after the `MessageService` transaction closes

1.4 WHEN the `ChatMessageHandler` iterates room members to broadcast unread notifications THEN the system fails to access `member.getUser().getId()` because the Hibernate session from `messageService.sendMessage()` has already ended

#### Expected Behavior (Correct)

2.3 WHEN a user sends a message via WebSocket (`/app/chat.send`) THEN the system SHALL successfully broadcast the message to the room topic and send unread notifications to all other room members without throwing `LazyInitializationException`

2.4 WHEN the `ChatMessageHandler` accesses entity associations for broadcasting THEN the system SHALL ensure all required entity data is available outside the service transaction boundary (either via eager fetching, DTOs, or a wrapping transaction)

#### Unchanged Behavior (Regression Prevention)

3.3 WHEN a user sends a message via the REST API (`POST /api/rooms/{roomId}/messages`) THEN the system SHALL CONTINUE TO return the created message response without errors

3.4 WHEN a message is sent THEN the system SHALL CONTINUE TO increment the room watermark and persist the message correctly

---

### Bug 3: Online Status Always Shows Gray (Offline)

#### Current Behavior (Defect)

1.5 WHEN a user's heartbeat is being recorded correctly in Redis and the backend logs confirm heartbeat processing THEN the frontend presence indicator always shows gray (offline) for other users

1.6 WHEN the frontend calls `fetchInitialPresence` or subscribes to `/topic/presence.{userId}` THEN the presence dots are not updated to reflect the actual ONLINE/AFK status

#### Expected Behavior (Correct)

2.5 WHEN a user has an active heartbeat recorded in Redis (within the TTL window) THEN the system SHALL return `ONLINE` or `AFK` status via the `/api/presence` endpoint and the frontend SHALL display the correct colored indicator (green for ONLINE, yellow for AFK)

2.6 WHEN a user's presence status changes THEN the system SHALL broadcast the update via `/topic/presence.{userId}` and the frontend SHALL update the presence dot color in real-time

#### Unchanged Behavior (Regression Prevention)

3.5 WHEN a user has no heartbeat recorded in Redis (key expired or never set) THEN the system SHALL CONTINUE TO return `OFFLINE` status

3.6 WHEN a user closes all browser tabs THEN the system SHALL CONTINUE TO transition their status to OFFLINE after the TTL expires

---

### Bug 4: Password Reset Doesn't Actually Change the Password

#### Current Behavior (Defect)

1.7 WHEN a user completes the password reset flow (requests token, submits new password via `POST /api/password/reset`) THEN the system returns a success response but the password is not actually changed — the old password still works for login

1.8 WHEN `PasswordService.resetPassword()` executes THEN the token is marked as used but the user's password hash is not persisted to the database

#### Expected Behavior (Correct)

2.7 WHEN a user submits a valid reset token and new password via `POST /api/password/reset` THEN the system SHALL update the user's password hash in the database so that the new password works for subsequent logins

2.8 WHEN the password reset completes successfully THEN the old password SHALL no longer be valid for authentication

#### Unchanged Behavior (Regression Prevention)

3.7 WHEN a user submits an expired or already-used reset token THEN the system SHALL CONTINUE TO reject the request with an appropriate error

3.8 WHEN a user changes their password via `POST /api/password/change` (logged-in flow) THEN the system SHALL CONTINUE TO update the password correctly

---

### Bug 5: Room Invitation to Non-Friend User Is Invisible

#### Current Behavior (Defect)

1.9 WHEN a room owner/admin sends an invitation to a non-friend user for a private room THEN the invitation is persisted in the database but the invitee has no way to discover or see it in the UI

1.10 WHEN a non-friend user receives a room invitation THEN the "Room Invitations" section in the sidebar does not appear or the invitation is not listed because the sidebar only loads after friends are populated and the invitation panel may not render for users without a friendship relationship to the inviter

#### Expected Behavior (Correct)

2.9 WHEN a user has pending room invitations THEN the system SHALL display them in the "Room Invitations" section of the sidebar regardless of whether the inviter is a friend

2.10 WHEN a non-friend user receives a room invitation THEN the system SHALL make the invitation visible and actionable (accept/decline) in the invitee's UI

#### Unchanged Behavior (Regression Prevention)

3.9 WHEN a user who is already a friend receives a room invitation THEN the system SHALL CONTINUE TO display the invitation in the sidebar correctly

3.10 WHEN a user accepts a room invitation THEN the system SHALL CONTINUE TO add them as a member of the private room


---

### Bug 6: Saved Messages Room Shows Internal UUID Name

#### Current Behavior (Defect)

1.11 WHEN a user views the "Saved Messages" room (in the room info panel or chat header) THEN the system displays the internal room name containing a UUID (e.g., "saved-messages-0d892201-8c42-44e0-b230-1c4fdd3c89bf") instead of a human-friendly name

#### Expected Behavior (Correct)

2.11 WHEN a user views the "Saved Messages" room THEN the system SHALL display "Saved messages" as the room name in all UI locations (room info panel, chat header)

#### Unchanged Behavior (Regression Prevention)

3.11 WHEN a user views any other room (public, private, or direct) THEN the system SHALL CONTINUE TO display the room's actual stored name

---

### Bug 7: Sessions Page Crashes with 500 Error (Thymeleaf Reserved Variable)

#### Current Behavior (Defect)

1.12 WHEN a user navigates to the sessions page (`/sessions` or `/profile/sessions`) THEN the system returns HTTP 500 with `IllegalArgumentException: Cannot set variable called 'session' into web variables map: such name is a reserved word` because the Thymeleaf template uses `th:each="session : ${sessions}"` and `session` is reserved in Thymeleaf's web context

#### Expected Behavior (Correct)

2.12 WHEN a user navigates to the sessions page THEN the system SHALL render the page successfully, listing all active sessions for the user without errors

#### Unchanged Behavior (Regression Prevention)

3.12 WHEN the sessions page renders THEN the system SHALL CONTINUE TO display correct session details (device, last active time, current session indicator)
