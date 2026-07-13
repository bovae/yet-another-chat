# Implementation Plan: Comprehensive Test Suite for YAC

## Overview

This plan implements a comprehensive test suite for the YAC application in incremental steps: first fix the CSRF bug blocking room creation, then add unit tests for all 13 services, parameterized boundary tests, integration tests for all REST/WebSocket flows, and finally Playwright UI test documentation. Each step builds on the previous, and checkpoints verify correctness before proceeding.

## Tasks

- [x] 1. Fix CSRF bug in rooms/create.html
  - [x] 1.1 Add missing CSRF meta tags to `src/main/resources/templates/rooms/create.html`
    - Add `<meta name="_csrf" th:content="${_csrf.token}">` and `<meta name="_csrf_header" th:content="${_csrf.headerName}">` to the `<head>` section, matching the pattern used in all other Thymeleaf templates
    - This fixes the 403 error on room creation where JavaScript reads CSRF tokens from these meta tags
    - _Requirements: 1.1, 1.2, 1.3_

- [x] 2. Unit tests for User, Auth, and Password services
  - [x] 2.1 Create `src/test/java/com/bovae/yac/unit/UserServiceTest.java`
    - Mock `UserRepository`, `RoomRepository`, `RoomMemberRepository`, `UnreadMarkerRepository`, `FriendshipRepository`, `UserBanRepository`, `PasswordResetTokenRepository`, `PasswordEncoder`, `FindByIndexNameSessionRepository`, `RoomService`
    - Test: registration with unique email/username creates user with BCrypt hash (CP 2)
    - Test: registration with duplicate email throws `ConflictException` (CP 1)
    - Test: registration with duplicate username throws `ConflictException` (CP 1)
    - Test: `updateProfile` with changed username throws `ForbiddenException` (CP 3)
    - Test: `deleteAccount` removes owned rooms, memberships, friendships, user bans, unread markers, password reset tokens, and sessions (CP 8)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 2.2 Create `src/test/java/com/bovae/yac/unit/AuthServiceTest.java`
    - Mock `UserRepository`, `FindByIndexNameSessionRepository`
    - Test: `loadUserByUsername` with valid email returns `UserDetails` with matching credentials (CP 4)
    - Test: `loadUserByUsername` with unknown email throws `UsernameNotFoundException` (CP 4)
    - Test: `terminateSession` deletes only the specified session (CP 5)
    - _Requirements: 2.6, 2.7, 2.8_

  - [x] 2.3 Create `src/test/java/com/bovae/yac/unit/PasswordServiceTest.java`
    - Mock `PasswordResetTokenRepository`, `UserRepository`, `PasswordEncoder`
    - Test: `createResetToken` returns raw token and persists hashed token (CP 7)
    - Test: `resetPassword` with valid token updates password hash and marks token used (CP 7)
    - Test: `resetPassword` with expired token throws `ForbiddenException` (CP 7)
    - Test: `changePassword` with correct current password updates hash (CP 6)
    - Test: `changePassword` with incorrect current password throws `ForbiddenException` (CP 6)
    - _Requirements: 2.9, 2.10, 2.11, 2.12, 2.13_

- [x] 3. Unit tests for Room, RoomMember, and Moderation services
  - [x] 3.1 Create `src/test/java/com/bovae/yac/unit/RoomServiceTest.java`
    - Mock `RoomRepository`, `RoomMemberRepository`, `MessageRepository`, `AttachmentRepository`, `RoomBanRepository`, `RoomInvitationRepository`, `UnreadMarkerRepository`
    - Test: `createRoom` with unique name creates room with owner as OWNER member (CP 12)
    - Test: `createRoom` with duplicate name throws `ConflictException` (CP 12)
    - Test: `deleteRoom` by owner cascades deletion of messages, attachments, members, bans, invitations (CP 8)
    - Test: `deleteRoom` by non-owner throws `ForbiddenException`
    - Test: `searchCatalog` returns only PUBLIC rooms matching search term (CP 13)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [x] 3.2 Create `src/test/java/com/bovae/yac/unit/RoomMemberServiceTest.java`
    - Mock `RoomMemberRepository`, `RoomBanRepository`, `RoomInvitationRepository`
    - Test: `joinPublicRoom` succeeds for non-banned user, fails for banned user (CP 14)
    - Test: `joinPrivateRoomViaInvitation` succeeds with valid invitation and deletes invitation (CP 14)
    - Test: `leaveRoom` removes member record, rejects owner from leaving (CP 15)
    - Test: joining a room user is already a member of throws `ConflictException`
    - _Requirements: 3.6, 3.7, 3.8, 14.1_

  - [x] 3.3 Create `src/test/java/com/bovae/yac/unit/ModerationServiceTest.java`
    - Mock `RoomMemberRepository`, `RoomBanRepository`, `MessageRepository`
    - Test: `kickMember` creates `RoomBan` and removes `RoomMember` (CP 16)
    - Test: `kickMember` on owner throws `ForbiddenException`
    - Test: `grantAdminRole` succeeds only when acting user is Owner (CP 16)
    - Test: `revokeAdminRole` on Owner throws `ForbiddenException` (CP 16)
    - Test: `deleteMessage` by admin permanently removes message (CP 16)
    - _Requirements: 3.9, 3.10, 3.11, 3.12, 14.8_

- [x] 4. Unit tests for Messaging, Notification, and DirectChat services
  - [x] 4.1 Create `src/test/java/com/bovae/yac/unit/MessageServiceTest.java`
    - Mock `MessageRepository`, `RoomRepository`, `RoomMemberService`, `UserBanService`, `RoomMemberRepository`
    - Test: `sendMessage` persists message with correct watermark and increments room watermark (CP 22)
    - Test: `sendMessage` rejects content exceeding 3072 UTF-8 bytes (CP 17)
    - Test: `sendMessage` rejects non-member sender (CP 17)
    - Test: `editMessage` updates content and sets edited flag to true (CP 18)
    - Test: `editMessage` by non-author throws `ForbiddenException` (CP 18)
    - Test: `deleteMessage` by author permanently removes message (CP 19)
    - Test: `getMessageHistory` returns messages ordered by watermark with correct cursor pagination (CP 23)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

  - [x] 4.2 Create `src/test/java/com/bovae/yac/unit/NotificationServiceTest.java`
    - Mock `UnreadMarkerRepository`, `SimpMessagingTemplate`
    - Test: `markRoomAsRead` sets lastReadWatermark to current room watermark minus one (CP 24)
    - Test: `computeUnreadCount` returns `min(room.nextWatermark - 1 - lastReadWatermark, 999)` (CP 24)
    - _Requirements: 4.8, 4.9_

  - [x] 4.3 Create `src/test/java/com/bovae/yac/unit/DirectChatServiceTest.java`
    - Mock `RoomRepository`, `RoomMemberRepository`, `FriendshipService`, `UserBanService`
    - Test: `getOrCreateDirectChat` succeeds only when users are friends and no mutual `UserBan` exists (CP 20)
    - Test: `getOrCreateDirectChat` returns existing direct chat when one already exists (CP 20)
    - Test: `getOrCreateDirectChat` with self throws `ForbiddenException`
    - Test: creating a direct chat that already exists returns the existing room (idempotent)
    - _Requirements: 4.10, 4.11, 14.4, 14.7_

- [x] 5. Unit tests for Social, File, and Presence services
  - [x] 5.1 Create `src/test/java/com/bovae/yac/unit/FriendshipServiceTest.java`
    - Mock `FriendshipRepository`, `UserBanRepository`
    - Test: full lifecycle — send request (PENDING), accept (ACCEPTED), decline (DECLINED), remove (deleted) (CP 10)
    - Test: `sendFriendRequest` with existing `UserBan` throws `ForbiddenException` (CP 10)
    - Test: `sendFriendRequest` with existing friendship throws `ConflictException`
    - Test: `sendFriendRequest` to self throws `ConflictException`
    - Test: only recipient can accept or decline (throws `ForbiddenException` for non-recipient)
    - Test: only participants can remove friendship (throws `ForbiddenException` for non-participant)
    - _Requirements: 5.1, 5.2, 14.2, 14.6, 14.9, 14.10_

  - [x] 5.2 Create `src/test/java/com/bovae/yac/unit/UserBanServiceTest.java`
    - Mock `UserBanRepository`, `FriendshipRepository`
    - Test: `banUser` creates `UserBan` and deletes any existing `Friendship` (CP 11)
    - Test: `unbanUser` deletes the `UserBan` record (CP 11)
    - Test: banning a user who is already banned throws `ConflictException`
    - Test: banning self throws `ConflictException`
    - _Requirements: 5.3, 5.4, 14.3, 14.5_

  - [x] 5.3 Create `src/test/java/com/bovae/yac/unit/FileStorageServiceTest.java`
    - Mock `AttachmentRepository`, `RoomMemberService`, `FileStorageProperties`
    - Test: `uploadFile` stores file and creates `Attachment` preserving original file name (CP 21)
    - Test: `uploadFile` rejects files exceeding 20 MB and images exceeding 3 MB (CP 21)
    - Test: `downloadFile` succeeds for room member, throws `ForbiddenException` for non-member (CP 21)
    - _Requirements: 5.5, 5.6, 5.7_

  - [x] 5.4 Create `src/test/java/com/bovae/yac/unit/PresenceServiceTest.java`
    - Mock `StringRedisTemplate`, `SimpMessagingTemplate`, `UserRepository`
    - Test: `recordHeartbeat` with `active=true` results in ONLINE status (CP 9)
    - Test: `recordHeartbeat` with `active=false` results in AFK status (CP 9)
    - Test: `removePresence` results in OFFLINE status (CP 9)
    - _Requirements: 5.8, 5.9, 5.10_

- [x] 6. Checkpoint — Unit tests pass
  - Ensure all unit tests pass via `./mvnw test -pl . -Dtest="com.bovae.yac.unit.*"`, ask the user if questions arise.

- [x] 7. Parameterized boundary tests
  - [x] 7.1 Create `src/test/java/com/bovae/yac/parameterized/RegistrationParameterizedTest.java`
    - `@SpringBootTest` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test registration uniqueness with multiple email/username combinations including boundary-length values (CP 1)
    - Test password hash round-trip with ASCII, Unicode, and special character passwords (CP 2)
    - Use `@MethodSource` with at least 5 representative values per parameter
    - _Requirements: 6.4, 6.5_

  - [x] 7.2 Create `src/test/java/com/bovae/yac/parameterized/MessageContentParameterizedTest.java`
    - `@SpringBootTest` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test message content round-trip with plain text, multiline, emoji, exactly 3072 UTF-8 bytes, and exceeding 3072 bytes (CP 17)
    - Use `@MethodSource` with at least 5 representative values
    - _Requirements: 6.6_

  - [x] 7.3 Create `src/test/java/com/bovae/yac/parameterized/PaginationParameterizedTest.java`
    - `@SpringBootTest` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test watermark monotonicity with sequences of 1, 5, and 20 messages (CP 22)
    - Test cursor-based pagination with varying page sizes and cursor positions including empty rooms and rooms with exactly one page (CP 23)
    - _Requirements: 6.7, 6.8_

  - [x] 7.4 Create `src/test/java/com/bovae/yac/parameterized/UnreadCountParameterizedTest.java`
    - `@SpringBootTest` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test unread count computation with watermark values at 0, 1, 500, and values exceeding display cap of 999 (CP 24)
    - Use `@CsvSource` with at least 5 representative values
    - _Requirements: 6.9_

- [x] 8. Checkpoint — Unit and parameterized tests pass
  - Ensure all tests pass via `./mvnw test`, ask the user if questions arise.

- [x] 9. Integration tests for Room and Message API flows
  - [x] 9.1 Create `src/test/java/com/bovae/yac/integration/RoomApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test room creation with valid CSRF token returns 201 (verifies CSRF fix)
    - Test catalog search returns only PUBLIC rooms matching search term
    - Test join public room, leave room, delete room by owner
    - Test unauthenticated POST to `/api/rooms` returns 401
    - _Requirements: 1.1, 1.3, 7.2_

  - [x] 9.2 Create `src/test/java/com/bovae/yac/integration/MessageApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test send message, edit message, delete message by author
    - Test cursor-paginated message history with `has_more` and `next_cursor` fields
    - Assert JSON uses snake_case field names (`$.sender_id`, `$.created_at`, `$.reply_to_id`)
    - _Requirements: 7.3_

  - [x] 9.3 Create `src/test/java/com/bovae/yac/integration/FriendshipApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test friend request send, accept, decline, list, and remove flows
    - _Requirements: 7.4_

  - [x] 9.4 Create `src/test/java/com/bovae/yac/integration/UserBanApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test ban and unban flows, verify friendship termination side effect on ban
    - _Requirements: 7.5_

- [x] 10. Integration tests for Moderation, Invitations, and DirectChat
  - [x] 10.1 Create `src/test/java/com/bovae/yac/integration/ModerationApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test kick member, ban/unban from room, grant/revoke admin role, admin message deletion
    - _Requirements: 7.6_

  - [x] 10.2 Create `src/test/java/com/bovae/yac/integration/RoomInvitationApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test invite user to private room, accept invitation, non-invited user join rejection
    - _Requirements: 7.7_

  - [x] 10.3 Create `src/test/java/com/bovae/yac/integration/DirectChatApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test create/get direct chat between friends, list direct chats
    - _Requirements: 7.8_

  - [x] 10.4 Create `src/test/java/com/bovae/yac/integration/AttachmentApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test file upload, download, and non-member access denial (403)
    - _Requirements: 7.9_

- [x] 11. Integration tests for Password, Account, Session, and Web flows
  - [x] 11.1 Create `src/test/java/com/bovae/yac/integration/PasswordApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test password change and password reset flows
    - _Requirements: 7.10_

  - [x] 11.2 Create `src/test/java/com/bovae/yac/integration/AccountDeletionApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test account deletion and verify cascade effects (rooms, memberships, friendships, bans removed)
    - _Requirements: 7.11_

  - [x] 11.3 Create `src/test/java/com/bovae/yac/integration/SessionApiIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test session listing and session termination
    - _Requirements: 7.12_

  - [x] 11.4 Create `src/test/java/com/bovae/yac/integration/WebControllerIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test `/login`, `/register`, `/chat`, `/rooms/catalog`, `/profile` return 200 with correct templates for authenticated users
    - Test unauthenticated access to `/chat` redirects to `/login`
    - Test public paths (`/login`, `/register`, `/api/health`) accessible without authentication
    - _Requirements: 7.13, 7.14, 14.12_

- [x] 12. Checkpoint — Integration tests for API flows pass
  - Ensure all integration tests pass via `./mvnw verify`, ask the user if questions arise.

- [x] 13. Access control and authorization integration tests
  - [x] 13.1 Create `src/test/java/com/bovae/yac/integration/AccessControlIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test non-member requesting room messages receives 403
    - Test non-member downloading attachment receives 403
    - Test non-admin attempting kick receives 403
    - Test non-admin attempting message deletion receives 403
    - Test non-owner attempting admin grant receives 403
    - Test user editing another user's message receives 403
    - Test user deleting another user's message (without admin role) receives 403
    - Test banned user attempting room join receives 403
    - Test blocked user sending friend request receives 403
    - Test blocked user sending message in frozen direct chat receives 403
    - Test unauthenticated request to any `/api/**` endpoint receives 401
    - Test non-invited user joining private room receives 403
    - Test CSRF tokens required for POST/PUT/DELETE to `/api/**`
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11, 9.12, 14.11_

- [x] 14. Multi-user scenario integration tests
  - [x] 14.1 Create `src/test/java/com/bovae/yac/integration/MultiUserScenarioIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test full friend request flow: User A sends, User B accepts, both see each other in friend list
    - Test ban side effects: User A bans User B, B cannot send messages or friend requests, existing friendship deleted
    - Test ban + direct chat: banning makes direct chat read-only for both participants
    - Test admin kick: kicked member cannot access room messages, RoomBan created
    - Test owner account deletion: owned rooms and contents cascade-deleted, other users' memberships removed
    - Test message exchange: two users exchange messages in room, both see messages in correct watermark order
    - Test private room invitation flow: owner invites user, user accepts, user becomes member and can send messages
    - Test unban: after unban, user can send friend requests again but deleted friendship is not restored
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_

- [x] 15. Presence and notification integration tests
  - [x] 15.1 Add presence and notification tests to existing integration test infrastructure or create dedicated IT class
    - Test active heartbeat changes user status to ONLINE
    - Test inactive heartbeat changes user status to AFK
    - Test removing presence sets user status to OFFLINE
    - Test sending messages increments unread count for non-viewing members
    - Test opening a room resets unread count to zero
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 16. Input validation and error handling integration tests
  - [x] 16.1 Create `src/test/java/com/bovae/yac/integration/ValidationErrorIT.java`
    - `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfig.class)` + `@Transactional`
    - Test blank message content returns 400
    - Test message exceeding 3072 UTF-8 bytes returns 400
    - Test blank room name returns 400
    - Test room name exceeding 100 characters returns 400
    - Test invalid email format on password reset returns 400
    - Test non-existent room returns 404
    - Test non-existent message returns 404
    - Test non-existent friendship returns 404
    - Test `ConflictException` maps to 409
    - Test `FileStorageException` maps to 400
    - Test malformed UUID path variables return 400
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8, 13.9, 13.10, 13.11_

- [x] 17. WebSocket integration tests
  - [x] 17.1 Create `src/test/java/com/bovae/yac/integration/WebSocketIT.java`
    - `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` + `@Import(TestcontainersConfig.class)`
    - Test STOMP message to `/app/chat.send` persists message and broadcasts to `/topic/room.{roomId}`
    - Test heartbeat to `/app/presence.heartbeat` with `active=true` updates presence in Redis
    - Test typing indicator to `/app/typing` broadcasts to `/topic/room.{roomId}.events`
    - Test `@MessageExceptionHandler` sends error payloads to `/user/queue/errors`
    - Test unauthenticated STOMP connections are rejected
    - Test message sent by User A is received by User B subscribed to room topic
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

- [x] 18. Checkpoint — All integration tests pass
  - Ensure all tests pass via `./mvnw verify`, ask the user if questions arise.

- [x] 19. Playwright UI test documentation
  - [x] 19.1 Create `src/test/e2e/README.md` with Playwright MCP workflow scripts
    - Document registration flow: navigate to `/register`, fill form, submit, verify redirect to login
    - Document login flow: navigate to `/login`, enter credentials, submit, verify redirect to `/chat`
    - Document room creation flow: from chat page, create public room, verify room appears in sidebar
    - Document messaging flow: enter room, send message, verify message appears
    - Document catalog search flow: navigate to catalog, search by name, verify results
    - Document logout flow: click logout, verify redirect to login
    - Include instructions for starting the application via `docker compose up`
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8_

- [x] 20. Final checkpoint — Full test suite passes
  - Ensure all existing property tests still pass
  - Ensure all unit tests pass via `./mvnw test`
  - Ensure all integration tests pass via `./mvnw verify`
  - Verify test organization matches the directory structure in the design document
  - Ensure all 15 requirements are covered by at least one test
  - _Requirements: 6.1, 6.2, 6.3, 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 15.10_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP — none in this plan since all tests are core deliverables
- Each task references specific requirements for traceability
- Checkpoints at tasks 6, 8, 12, 18, and 20 ensure incremental validation
- The CSRF fix (task 1) must be completed first as it unblocks room-related integration and UI tests
- All existing 14 jqwik property-based tests remain unchanged throughout
- Integration tests use `*IT.java` naming convention for maven-failsafe-plugin recognition
- Unit tests use `*Test.java` naming convention for maven-surefire-plugin recognition
- JSON assertions must use snake_case field names to match the global Jackson `SnakeCaseStrategy`
