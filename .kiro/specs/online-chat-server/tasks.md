# Implementation Plan: Online Chat Server (YAC)

## Overview

Incremental build of the YAC chat server on the existing Spring Boot 4 scaffold. Each task builds on the previous, starting with entity modifications and core services, then layering controllers, WebSocket handlers, and frontend wiring. jqwik property tests validate correctness properties from the design. Testcontainers provide PostgreSQL + Redis for integration tests.

## Tasks

- [x] 1. Entity modifications, new entity, and Liquibase migration
  - [x] 1.1 Add `watermark` field to Message entity, `nextWatermark` field to Room entity, replace `unreadCount`/`lastReadMessage` with `lastReadWatermark` on UnreadMarker entity
    - Modify `Message.java`: add `@Column(nullable = false) private Long watermark;`
    - Modify `Room.java`: add `@Column(name = "next_watermark", nullable = false) private Long nextWatermark = 1L;`
    - Modify `UnreadMarker.java`: remove `lastReadMessage` and `unreadCount`, add `@Column(name = "last_read_watermark") private Long lastReadWatermark;`
    - Update `UnreadMarkerId` if needed
    - _Requirements: 16.4, 17.4_

  - [x] 1.2 Create PasswordResetToken entity and PasswordResetTokenRepository
    - Create `PasswordResetToken.java` with id, user FK, tokenHash (unique), expiresAt, used, createdAt
    - Create `PasswordResetTokenRepository.java`
    - _Requirements: 3.1, 3.2_

  - [x] 1.3 Create ConflictException
    - Add `ConflictException extends RuntimeException` in exception package
    - Register in `GlobalApiExceptionHandler` returning 409
    - _Requirements: 8.3, 1.2, 1.3_

  - [x] 1.4 Write Liquibase migration 002 for schema changes
    - Add `watermark BIGINT NOT NULL DEFAULT 0` to messages table
    - Add `next_watermark BIGINT NOT NULL DEFAULT 1` to rooms table
    - Alter unread_markers: drop `last_read_message_id` and `unread_count`, add `last_read_watermark BIGINT`
    - Create `password_reset_tokens` table
    - Create all indexes from design (idx_messages_room_watermark, idx_rooms_visibility_name, idx_unread_markers_user, idx_friendships_requester, idx_friendships_recipient, idx_room_bans_room_user, idx_user_bans_blocker, idx_user_bans_blocked, idx_room_members_room)
    - Update `db.changelog-master.yaml` to include the new migration
    - _Requirements: 16.4, 17.4, 3.1, 20.5_

  - [x] 1.5 Create all DTOs (records) needed across the application
    - `MessagePageRequest`, `MessagePage`, `ChatMessageResponse`, `ChatMessageRequest` (update existing), `PresenceUpdate`, `RoomCatalogEntry`, `ErrorResponse` (update if needed), `NotificationEvent`, `RoomEvent`
    - Create request DTOs: `CreateRoomRequest`, `SendFriendRequest`, `PasswordResetRequest`, `PasswordChangeRequest`, `PasswordResetConfirmRequest`
    - Use Jakarta validation annotations, `@JsonNaming(SnakeCaseStrategy)`, records per conventions
    - _Requirements: 13.1, 13.2, 16.2, 5.4, 8.1_

  - [x] 1.6 Add repository query methods needed by services
    - `MessageRepository`: `findByRoomAndWatermarkGreaterThanOrderByWatermarkAsc(Room, Long, Pageable)`
    - `RoomRepository`: `findByVisibilityAndNameContainingIgnoreCase`, `existsByName`
    - `FriendshipRepository`: find by requester/recipient and status combinations
    - `RoomMemberRepository`: `findByRoom`, `findByUser`, `existsByRoomAndUser`
    - `RoomBanRepository`: `existsByRoomAndUser`
    - `UserBanRepository`: `existsByBlockerAndBlocked`, find by blocker or blocked
    - `RoomInvitationRepository`: `findByRoomAndInvitee`
    - `UnreadMarkerRepository`: `findByUser`
    - _Requirements: 9.1, 9.2, 16.2, 7.2, 12.5_

- [x] 2. Checkpoint — Verify entity changes compile and migration runs
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Core services — User, Auth, Password
  - [x] 3.1 Implement UserService
    - Registration (email/username uniqueness, BCrypt hash, save)
    - Profile update (displayName only, username immutable)
    - Account deletion cascade (delete owned rooms + messages + attachments + members + bans + invitations, remove memberships, delete friendships, delete user bans, invalidate sessions)
    - User search by username
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 4.1, 4.2, 4.3, 4.4, 4.5_

  - [x] 3.2 Write property tests for UserService (RegistrationPropertyTest)
    - **Property 1: Registration uniqueness enforcement**
    - **Property 2: Password hash round-trip**
    - **Property 3: Username immutability**
    - **Validates: Requirements 1.2, 1.3, 1.4, 1.5**

  - [x] 3.3 Write property test for account deletion (AccountDeletionPropertyTest)
    - **Property 8: Account deletion cascade**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4, 11.3, 11.4**

  - [x] 3.4 Implement AuthService
    - Spring Security `UserDetailsService` implementation loading by email
    - Session listing (query Redis for user sessions)
    - Session termination (invalidate specific session by ID)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [x] 3.5 Write property tests for AuthService (AuthPropertyTest)
    - **Property 4: Authentication round-trip**
    - **Property 5: Session isolation**
    - **Validates: Requirements 2.1, 2.2, 2.4, 2.5, 2.6**

  - [x] 3.6 Implement PasswordService
    - Reset token generation (create PasswordResetToken with hashed token, return raw token)
    - Reset token validation and password update (verify hash, check expiry, mark used, update password)
    - Password change (verify current password, update hash)
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 3.7 Write property tests for PasswordService (AuthPropertyTest)
    - **Property 6: Password change authorization**
    - **Property 7: Password reset token lifecycle**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4**

- [x] 4. Checkpoint — Core user services verified
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Room and membership services
  - [x] 5.1 Implement RoomService
    - Create room (unique name check, set owner, add RoomMember with OWNER role, init nextWatermark)
    - Delete room (cascade: messages, attachments, members, bans, invitations)
    - Room catalog search (PUBLIC only, name/description filter, paginated)
    - Get room details
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 9.1, 9.2, 11.3, 11.4_

  - [x] 5.2 Write property tests for RoomService (RoomPropertyTest)
    - **Property 12: Room creation invariants**
    - **Property 13: Room catalog visibility filtering**
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 9.1, 9.2, 10.1**

  - [x] 5.3 Implement RoomMemberService
    - Join public room (check RoomBan, add member)
    - Join private room via invitation (check RoomInvitation, add member, delete invitation)
    - Leave room (reject if owner, remove RoomMember)
    - List members, check membership
    - _Requirements: 9.3, 9.4, 10.3, 10.4, 11.1, 11.2_

  - [x] 5.4 Write property tests for RoomMemberService (RoomPropertyTest)
    - **Property 14: Room join access control**
    - **Property 15: Room membership lifecycle**
    - **Validates: Requirements 9.3, 9.4, 10.3, 10.4, 11.1, 11.2, 11.5**

- [x] 6. Friendship, UserBan, and Moderation services
  - [x] 6.1 Implement FriendshipService
    - Send request (create PENDING, optional text), accept, decline, remove
    - List friends with presence status
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [x] 6.2 Write property test for FriendshipService (FriendshipPropertyTest)
    - **Property 10: Friendship lifecycle state transitions**
    - **Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5**

  - [x] 6.3 Implement UserBanService
    - Ban user (create UserBan, terminate friendship if exists, freeze direct chat if exists)
    - Unban user (delete UserBan, restore friend request ability)
    - Check ban existence for message/request guards
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x] 6.4 Write property test for UserBanService (UserBanPropertyTest)
    - **Property 11: UserBan access control enforcement**
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 14.3**

  - [x] 6.5 Implement ModerationService
    - Kick member (create RoomBan, remove RoomMember)
    - Ban/unban from room
    - Delete message (admin)
    - Grant/revoke admin role (owner only for grant, admin can demote non-owner admin)
    - Reject demoting owner
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8_

  - [x] 6.6 Write property test for ModerationService (ModerationPropertyTest)
    - **Property 16: Admin moderation actions**
    - **Validates: Requirements 12.1, 12.3, 12.4, 12.6, 12.7, 12.8**

- [x] 7. Checkpoint — Room and social services verified
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Messaging, attachments, and direct chat services
  - [x] 8.1 Implement MessageService
    - Send message: validate membership, validate content size (≤3072 bytes UTF-8), atomically increment room.nextWatermark, assign watermark, persist, broadcast via STOMP
    - Edit message (author only, update content, set edited=true)
    - Delete message (author or admin)
    - Cursor-based paginated history (watermark > cursor, ordered asc, limited by size, return MessagePage with nextCursor and hasMore)
    - Check UserBan before sending in direct chats
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8, 16.1, 16.2, 16.3, 16.4, 16.5_

  - [x] 8.2 Write property tests for MessageService (MessagePropertyTest)
    - **Property 17: Message content round-trip**
    - **Property 18: Message edit invariant**
    - **Property 19: Message deletion by author**
    - **Validates: Requirements 13.1, 13.2, 13.3, 13.4, 13.6, 13.8**

  - [x] 8.3 Write property tests for watermark and pagination (WatermarkPropertyTest)
    - **Property 22: Watermark monotonicity and message ordering**
    - **Property 23: Cursor-based pagination correctness**
    - **Validates: Requirements 16.1, 16.2, 16.4, 16.5**

  - [x] 8.4 Implement DirectChatService
    - Create/get direct chat (check friendship, check no mutual UserBan, create DIRECT room with both as members)
    - List user's direct chats
    - Enforce no admin moderation in direct chats
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_

  - [x] 8.5 Write property test for DirectChatService (DirectChatPropertyTest)
    - **Property 20: Direct chat access control**
    - **Validates: Requirements 14.1, 14.2, 14.5**

  - [x] 8.6 Implement FileStorageService
    - Upload file (validate size: ≤20MB files, ≤3MB images; store to filesystem; create Attachment record)
    - Download file (check room membership; serve file)
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9_

  - [x] 8.7 Write property test for FileStorageService (AttachmentPropertyTest)
    - **Property 21: Attachment upload and access control**
    - **Validates: Requirements 15.1, 15.2, 15.5, 15.6, 15.8, 15.9**

  - [x] 8.8 Implement NotificationService
    - Update UnreadMarker lastReadWatermark when user opens room
    - Compute unread count on demand: `min(room.nextWatermark - 1 - lastReadWatermark, displayCap)`
    - Broadcast notification events via `/user/queue/notifications`
    - _Requirements: 17.1, 17.2, 17.3, 17.4_

  - [x] 8.9 Write property test for NotificationService (UnreadPropertyTest)
    - **Property 24: Unread count computation**
    - **Validates: Requirements 17.1, 17.2, 17.4**

- [x] 9. Checkpoint — All services verified
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Presence service with Redis
  - [x] 10.1 Implement PresenceService
    - Record heartbeat: parse active flag, store in Redis hash `presence:{userId}` with 90s TTL
    - Compute status: ONLINE (active heartbeat within interval), AFK (heartbeats arriving but no active tabs > 1min), OFFLINE (no heartbeats within timeout)
    - Scheduled cleanup (`@Scheduled` every 30s): scan stale entries, broadcast OFFLINE
    - Broadcast presence changes via `/topic/presence.{userId}`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [x] 10.2 Write property test for PresenceService (PresencePropertyTest)
    - **Property 9: Presence status computation**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.6**

- [x] 11. REST API controllers
  - [x] 11.1 Implement MessageApiController
    - `GET /api/rooms/{roomId}/messages` — cursor-paginated history
    - `POST /api/rooms/{roomId}/messages` — send message
    - `PUT /api/rooms/{roomId}/messages/{id}` — edit message
    - `DELETE /api/rooms/{roomId}/messages/{id}` — delete message
    - All endpoints check room membership via service layer
    - _Requirements: 13.1, 13.4, 13.6, 16.2_

  - [x] 11.2 Implement RoomApiController
    - `GET /api/rooms` — catalog search (PUBLIC only)
    - `POST /api/rooms` — create room
    - `DELETE /api/rooms/{id}` — delete room (owner only)
    - `POST /api/rooms/{id}/join` — join public room
    - `POST /api/rooms/{id}/leave` — leave room
    - _Requirements: 8.1, 8.3, 9.1, 9.2, 9.3, 11.1, 11.2_

  - [x] 11.3 Implement RoomMemberApiController, RoomBanApiController, RoomInvitationApiController
    - Members: `GET /api/rooms/{roomId}/members`, `DELETE /{userId}` (kick), `PUT /{userId}/role`
    - Bans: `GET /api/rooms/{roomId}/bans`, `POST` (ban), `DELETE /{userId}` (unban)
    - Invitations: `POST /api/rooms/{roomId}/invitations`, `POST /{id}/accept`, `DELETE /{id}`
    - _Requirements: 10.2, 10.3, 12.1, 12.2, 12.3, 12.5, 12.7, 12.8_

  - [x] 11.4 Implement FriendshipApiController, UserBanApiController, DirectChatApiController
    - Friends: `GET /api/friends`, `POST /request`, `POST /{id}/accept`, `POST /{id}/decline`, `DELETE /{id}`
    - UserBans: `POST /api/user-bans`, `DELETE /{id}`
    - DirectChats: `POST /api/direct-chats`, `GET /api/direct-chats`
    - _Requirements: 6.1, 6.3, 6.4, 6.5, 7.1, 7.5, 14.1_

  - [x] 11.5 Implement UserApiController, SessionApiController, PasswordApiController
    - Users: `GET /api/users/search`, `DELETE /api/users/me`
    - Sessions: `GET /api/sessions`, `DELETE /api/sessions/{id}`
    - Password: `POST /api/password/reset-request`, `POST /api/password/reset`, `POST /api/password/change`
    - _Requirements: 2.5, 2.6, 3.1, 3.2, 3.3, 4.1_

  - [x] 11.6 Implement AttachmentApiController
    - `POST /api/rooms/{roomId}/attachments` — upload (multipart, validate size)
    - `GET /api/rooms/{roomId}/attachments/{id}/download` — download (check membership)
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.8, 15.9_

- [x] 12. Checkpoint — REST API controllers verified
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. STOMP WebSocket handlers
  - [x] 13.1 Implement ChatMessageHandler
    - `@MessageMapping("/chat.send")` — receive message, delegate to MessageService, broadcast to `/topic/room.{roomId}`
    - `@MessageExceptionHandler` — send errors to `/user/queue/errors`
    - _Requirements: 13.1, 18.1, 18.2_

  - [x] 13.2 Implement PresenceHandler
    - `@MessageMapping("/presence.heartbeat")` — receive heartbeat with active flag, delegate to PresenceService
    - _Requirements: 5.5, 5.6_

  - [x] 13.3 Implement TypingHandler (optional)
    - `@MessageMapping("/typing")` — broadcast typing indicator to `/topic/room.{roomId}.events`
    - _Requirements: 19.4_

  - [x] 13.4 Update WebSocketConfig and SecurityConfig for STOMP subscriptions
    - Ensure `/topic/room.*`, `/topic/presence.*`, `/user/queue/**` subscription patterns are allowed
    - Add WebSocket security rules: authenticated users only for `/app/**` destinations
    - Update `SecurityConfig` to permit `/ws/**` and `/api/health`
    - _Requirements: 18.2, 20.1_

- [x] 14. Web controllers and Thymeleaf templates
  - [x] 14.1 Implement RoomWebController
    - `GET /rooms/catalog` — render room catalog page
    - `GET /rooms/create` — render room creation form
    - _Requirements: 9.1, 8.1_

  - [x] 14.2 Update AuthWebController with registration POST handler
    - `POST /register` — delegate to UserService, redirect to login on success
    - `POST /forgot-password` — delegate to PasswordService
    - _Requirements: 1.1, 3.1_

  - [x] 14.3 Update ChatWebController for room view
    - `GET /chat/rooms/{id}` — render room chat view with member list, message area
    - Pass room details, initial messages, member list to model
    - _Requirements: 19.1, 19.2, 19.3_

  - [x] 14.4 Update ProfileWebController
    - `GET /profile` — render profile with display name edit
    - `GET /profile/sessions` — render active sessions list
    - _Requirements: 2.5_

  - [x] 14.5 Update Thymeleaf templates and fragments
    - Update `chat/index.html` — sidebar with rooms/contacts, message area, input area
    - Update `fragments/sidebar.html` — room list with unread badges, friend list with presence dots
    - Update `fragments/message-item.html` — reply quotes, edited indicator, admin delete button
    - Update `fragments/message-input.html` — multiline, emoji, file/image buttons, reply-to control
    - Update `fragments/member-list.html` — presence status, admin actions
    - Create room catalog and creation templates
    - Create modal fragments for admin actions (ban, kick, role management)
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5_

- [x] 15. Frontend JavaScript — STOMP client and presence heartbeat
  - [x] 15.1 Update `stomp-client.js`
    - Connect to `/ws` endpoint, subscribe to room topics and user queue
    - Handle incoming messages: append to DOM or trigger HTMX swap
    - Handle reconnection with cursor-based history gap detection
    - _Requirements: 18.1, 18.2, 16.5, 16.6, 16.7_

  - [x] 15.2 Update `presence.js`
    - Track cursor movement events per tab
    - Send periodic heartbeats via STOMP with `{active: true/false, timestamp}`
    - Active = cursor moved within last 2 seconds
    - _Requirements: 5.1, 5.5, 5.6_

  - [x] 15.3 Update `app.js`
    - Infinite scroll: detect scroll-to-top, fetch older messages via REST, prepend
    - Auto-scroll to bottom on new message (only if already at bottom)
    - Unread badge updates from `/user/queue/notifications`
    - Clipboard paste handler for image uploads
    - _Requirements: 16.2, 16.6, 16.7, 17.3, 15.7_

- [x] 16. Checkpoint — Full stack wiring verified
  - Ensure all tests pass, ask the user if questions arise.

- [x] 17. Add jqwik dependency and test infrastructure
  - [x] 17.1 Add jqwik dependency to pom.xml
    - Add `net.jqwik:jqwik` test dependency
    - Create base test configuration class with Testcontainers (PostgreSQL + Redis)
    - Create custom jqwik `@Provide` arbitraries for emails, usernames, passwords, message content, room configs, heartbeat sequences
    - _Requirements: 20.1_

- [x] 18. Integration tests
  - [x] 18.1 Write integration tests for REST API flows
    - Full HTTP request/response cycles for message CRUD, room CRUD, friendship lifecycle
    - WebSocket STOMP message flow end-to-end
    - File upload/download through filesystem
    - Cursor-based pagination with large datasets
    - _Requirements: 18.1, 16.3, 15.1, 15.8_

  - [x] 18.2 Write integration tests for presence and notifications
    - Heartbeat → status change → broadcast flow
    - Unread count computation and reset on room open
    - _Requirements: 5.4, 17.1, 17.2_

- [x] 19. Final checkpoint — All tests pass, full feature verified
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate the 24 correctness properties from the design document using jqwik
- Unit tests validate specific examples and edge cases
- XMPP/Jabber support (Requirement 21) is intentionally excluded — it's marked optional in requirements and should be a separate spec
- Existing scaffolded entities, repositories, configs, and templates are modified in-place rather than recreated
