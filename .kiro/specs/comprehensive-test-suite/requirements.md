# Requirements Document

## Introduction

The YAC (Yet Another Chat) application has a complete implementation with 13 services, 13 REST API controllers, 5 web controllers, 3 WebSocket handlers, and 14 JPA entities. The existing test suite is inadequate: property-based tests (jqwik) exist and pass but provide limited value for the types of logic being tested; integration tests cover only 3 REST flows and 1 presence/notification flow; unit tests are completely absent; and no UI/end-to-end tests exist. Additionally, a critical bug prevents room creation (HTTP 403), blocking manual testing of most features.

This spec defines a comprehensive test suite that keeps existing property-based tests as-is, adds unit tests for all service-layer business logic with Mockito, adds parameterized tests for input-dependent correctness properties, expands integration tests to cover all REST API and WebSocket flows with multi-user scenarios and access control verification, and introduces Playwright-based UI tests for user-facing workflows. The test suite validates all 24 correctness properties and 21 requirements from the original online-chat-server spec. The primary goal is to ensure all logic functions as expected and all tests pass.

## Glossary

- **Test_Suite**: The complete collection of unit tests, parameterized tests, integration tests, and UI tests for the YAC application
- **Unit_Test**: A JUnit 5 test that exercises a single service method in isolation using Mockito mocks for all dependencies
- **Parameterized_Test**: A JUnit 5 `@ParameterizedTest` that runs the same assertion against multiple input values, supplementing existing jqwik property-based tests with targeted boundary and edge case coverage
- **Integration_Test**: A `@SpringBootTest` test using Testcontainers (PostgreSQL + Redis) and MockMvc that exercises full HTTP request/response cycles through controllers and services against real infrastructure, including multi-user scenarios and access control verification
- **UI_Test**: A Playwright-based end-to-end test that drives a real browser against the running YAC application, validating user-facing workflows
- **Access_Control_Test**: A test that verifies users cannot access, modify, or delete resources they are not authorized to interact with (e.g., messages in rooms they are not members of, other users' messages, admin actions without admin role)
- **Multi_User_Scenario**: A test involving two or more authenticated users interacting through the system, verifying correct behavior of cross-user operations (friend requests, bans, room membership, message visibility)
- **Service_Layer**: The 13 Spring service classes (UserService, AuthService, PasswordService, RoomService, RoomMemberService, MessageService, FriendshipService, UserBanService, ModerationService, DirectChatService, FileStorageService, PresenceService, NotificationService)
- **Correctness_Property**: One of the 24 formal properties defined in the original design document that describe invariants the system must maintain
- **CSRF_Fix**: The resolution of the HTTP 403 error on room creation caused by missing CSRF meta tags in the `rooms/create.html` Thymeleaf template, preventing the JavaScript from sending the CSRF token with the API request
- **Testcontainers_Infrastructure**: The shared PostgreSQL 17 and Redis 7 containers managed by TestcontainersConfig for integration and property tests
- **MockMvc**: Spring's test framework for simulating HTTP requests against controllers without starting a real HTTP server

## Requirements

### Requirement 1: Fix Room Creation 403 Bug

**User Story:** As a developer, I want the room creation endpoint to accept authenticated requests, so that the application is functional and testable.

#### Acceptance Criteria

1. WHEN an authenticated User sends a POST request to `/api/rooms` with a valid CSRF token and room payload, THE Application SHALL create the room and return HTTP 201
2. WHEN an authenticated User submits the room creation form via the web UI, THE Application SHALL create the room and redirect to the chat page
3. IF an unauthenticated User sends a POST request to `/api/rooms`, THEN THE Application SHALL return HTTP 401

### Requirement 2: Unit Tests for User and Authentication Services

**User Story:** As a developer, I want unit tests for UserService, AuthService, and PasswordService, so that registration, authentication, session management, and password flows are verified in isolation.

#### Acceptance Criteria

1. THE Unit_Test for UserService SHALL verify that registration with a unique email and username creates a User with a BCrypt-hashed password (Correctness_Property 2)
2. THE Unit_Test for UserService SHALL verify that registration with a duplicate email throws ConflictException (Correctness_Property 1)
3. THE Unit_Test for UserService SHALL verify that registration with a duplicate username throws ConflictException (Correctness_Property 1)
4. THE Unit_Test for UserService SHALL verify that updateProfile with a changed username throws ForbiddenException (Correctness_Property 3)
5. THE Unit_Test for UserService SHALL verify that deleteAccount removes owned rooms, memberships, friendships, user bans, unread markers, password reset tokens, and sessions (Correctness_Property 8)
6. THE Unit_Test for AuthService SHALL verify that loadUserByUsername with a valid email returns UserDetails with matching credentials (Correctness_Property 4)
7. THE Unit_Test for AuthService SHALL verify that loadUserByUsername with an unknown email throws UsernameNotFoundException (Correctness_Property 4)
8. THE Unit_Test for AuthService SHALL verify that terminateSession deletes only the specified session (Correctness_Property 5)
9. THE Unit_Test for PasswordService SHALL verify that createResetToken returns a raw token and persists a hashed token (Correctness_Property 7)
10. THE Unit_Test for PasswordService SHALL verify that resetPassword with a valid token updates the password hash and marks the token as used (Correctness_Property 7)
11. THE Unit_Test for PasswordService SHALL verify that resetPassword with an expired token throws ForbiddenException (Correctness_Property 7)
12. THE Unit_Test for PasswordService SHALL verify that changePassword with the correct current password updates the hash (Correctness_Property 6)
13. THE Unit_Test for PasswordService SHALL verify that changePassword with an incorrect current password throws ForbiddenException (Correctness_Property 6)

### Requirement 3: Unit Tests for Room and Membership Services

**User Story:** As a developer, I want unit tests for RoomService, RoomMemberService, and ModerationService, so that room lifecycle, membership, and moderation logic are verified in isolation.

#### Acceptance Criteria

1. THE Unit_Test for RoomService SHALL verify that createRoom with a unique name creates a Room with the creator as Owner and a RoomMember with OWNER role (Correctness_Property 12)
2. THE Unit_Test for RoomService SHALL verify that createRoom with a duplicate name throws ConflictException (Correctness_Property 12)
3. THE Unit_Test for RoomService SHALL verify that deleteRoom by the owner cascades deletion of messages, attachments, members, bans, and invitations (Correctness_Property 8)
4. THE Unit_Test for RoomService SHALL verify that deleteRoom by a non-owner throws ForbiddenException
5. THE Unit_Test for RoomService SHALL verify that searchCatalog returns only PUBLIC rooms matching the search term (Correctness_Property 13)
6. THE Unit_Test for RoomMemberService SHALL verify that joinPublicRoom succeeds for a non-banned user and fails for a banned user (Correctness_Property 14)
7. THE Unit_Test for RoomMemberService SHALL verify that joinPrivateRoomViaInvitation succeeds with a valid invitation and deletes the invitation (Correctness_Property 14)
8. THE Unit_Test for RoomMemberService SHALL verify that leaveRoom removes the member record and rejects the owner from leaving (Correctness_Property 15)
9. THE Unit_Test for ModerationService SHALL verify that kickMember creates a RoomBan and removes the RoomMember (Correctness_Property 16)
10. THE Unit_Test for ModerationService SHALL verify that grantAdminRole succeeds only when the acting user is the Owner (Correctness_Property 16)
11. THE Unit_Test for ModerationService SHALL verify that revokeAdminRole on the Owner throws ForbiddenException (Correctness_Property 16)
12. THE Unit_Test for ModerationService SHALL verify that deleteMessage by an admin permanently removes the message (Correctness_Property 16)

### Requirement 4: Unit Tests for Messaging and Notification Services

**User Story:** As a developer, I want unit tests for MessageService, NotificationService, and DirectChatService, so that messaging, unread tracking, and direct chat logic are verified in isolation.

#### Acceptance Criteria

1. THE Unit_Test for MessageService SHALL verify that sendMessage persists a message with the correct watermark and increments the room watermark (Correctness_Property 22)
2. THE Unit_Test for MessageService SHALL verify that sendMessage rejects content exceeding 3072 UTF-8 bytes (Correctness_Property 17)
3. THE Unit_Test for MessageService SHALL verify that sendMessage rejects a non-member sender (Correctness_Property 17)
4. THE Unit_Test for MessageService SHALL verify that editMessage updates content and sets the edited flag to true (Correctness_Property 18)
5. THE Unit_Test for MessageService SHALL verify that editMessage by a non-author throws ForbiddenException (Correctness_Property 18)
6. THE Unit_Test for MessageService SHALL verify that deleteMessage by the author permanently removes the message (Correctness_Property 19)
7. THE Unit_Test for MessageService SHALL verify that getMessageHistory returns messages ordered by watermark with correct cursor pagination (Correctness_Property 23)
8. THE Unit_Test for NotificationService SHALL verify that markRoomAsRead sets lastReadWatermark to the current room watermark minus one (Correctness_Property 24)
9. THE Unit_Test for NotificationService SHALL verify that computeUnreadCount returns min(room.nextWatermark - 1 - lastReadWatermark, 999) (Correctness_Property 24)
10. THE Unit_Test for DirectChatService SHALL verify that getOrCreateDirectChat succeeds only when users are friends and no mutual UserBan exists (Correctness_Property 20)
11. THE Unit_Test for DirectChatService SHALL verify that getOrCreateDirectChat returns the existing Direct_Chat when one already exists (Correctness_Property 20)

### Requirement 5: Unit Tests for Social and File Services

**User Story:** As a developer, I want unit tests for FriendshipService, UserBanService, FileStorageService, and PresenceService, so that social interactions, file handling, and presence logic are verified in isolation.

#### Acceptance Criteria

1. THE Unit_Test for FriendshipService SHALL verify the full lifecycle: send request (PENDING), accept (ACCEPTED), decline (DECLINED), and remove (deleted) (Correctness_Property 10)
2. THE Unit_Test for FriendshipService SHALL verify that sendFriendRequest with an existing UserBan throws ForbiddenException (Correctness_Property 10)
3. THE Unit_Test for UserBanService SHALL verify that banUser creates a UserBan and deletes any existing Friendship (Correctness_Property 11)
4. THE Unit_Test for UserBanService SHALL verify that unbanUser deletes the UserBan record (Correctness_Property 11)
5. THE Unit_Test for FileStorageService SHALL verify that uploadFile stores the file and creates an Attachment record preserving the original file name (Correctness_Property 21)
6. THE Unit_Test for FileStorageService SHALL verify that uploadFile rejects files exceeding 20 MB and images exceeding 3 MB (Correctness_Property 21)
7. THE Unit_Test for FileStorageService SHALL verify that downloadFile succeeds for a room member and throws ForbiddenException for a non-member (Correctness_Property 21)
8. THE Unit_Test for PresenceService SHALL verify that recordHeartbeat with active=true results in ONLINE status (Correctness_Property 9)
9. THE Unit_Test for PresenceService SHALL verify that recordHeartbeat with active=false results in AFK status (Correctness_Property 9)
10. THE Unit_Test for PresenceService SHALL verify that removePresence results in OFFLINE status (Correctness_Property 9)

### Requirement 6: Supplement Property-Based Tests with Parameterized Tests

**User Story:** As a developer, I want parameterized tests that cover input-dependent correctness properties with representative boundary values, so that edge cases are explicitly verified alongside the existing property-based tests.

#### Acceptance Criteria

1. THE Test_Suite SHALL keep all existing jqwik property-based test files in `src/test/java/com/bovae/yac/property/` unchanged
2. THE Test_Suite SHALL create parameterized tests in `src/test/java/com/bovae/yac/parameterized/` that supplement the 24 Correctness_Properties with explicit boundary and edge case inputs
3. WHEN a Correctness_Property involves input-dependent behavior (message content sizes, email formats, password variations), THE Parameterized_Test SHALL use `@MethodSource` or `@CsvSource` with at least 5 representative values including boundary cases
4. THE Parameterized_Test for registration uniqueness (Correctness_Property 1) SHALL test with multiple email and username combinations including boundary-length values
5. THE Parameterized_Test for password hash round-trip (Correctness_Property 2) SHALL test with passwords containing ASCII, Unicode, and special characters
6. THE Parameterized_Test for message content round-trip (Correctness_Property 17) SHALL test with plain text, multiline text, emoji, exactly 3072 UTF-8 bytes, and content exceeding 3072 bytes
7. THE Parameterized_Test for watermark monotonicity (Correctness_Property 22) SHALL test with sequences of 1, 5, and 20 messages verifying strict ordering
8. THE Parameterized_Test for cursor-based pagination (Correctness_Property 23) SHALL test with varying page sizes and cursor positions including empty rooms and rooms with exactly one page of messages
9. THE Parameterized_Test for unread count computation (Correctness_Property 24) SHALL test with watermark values at 0, 1, 500, and values exceeding the display cap of 999

### Requirement 7: Integration Tests for REST API Flows

**User Story:** As a developer, I want integration tests covering all REST API controller flows, so that HTTP request/response cycles are validated against real infrastructure.

#### Acceptance Criteria

1. THE Integration_Test SHALL use MockMvc with `@SpringBootTest` and Testcontainers_Infrastructure for all REST API tests
2. THE Integration_Test SHALL cover the room creation, catalog search, join, leave, and deletion flows via RoomApiController (Correctness_Properties 12, 13, 14, 15)
3. THE Integration_Test SHALL cover the message send, edit, delete, and cursor-paginated history flows via MessageApiController (Correctness_Properties 17, 18, 19, 22, 23)
4. THE Integration_Test SHALL cover the friendship request, accept, decline, list, and remove flows via FriendshipApiController (Correctness_Property 10)
5. THE Integration_Test SHALL cover the user ban and unban flows via UserBanApiController, verifying friendship termination side effects (Correctness_Property 11)
6. THE Integration_Test SHALL cover the room moderation flows (kick, ban, unban, role management, message deletion) via RoomMemberApiController and RoomBanApiController (Correctness_Property 16)
7. THE Integration_Test SHALL cover the private room invitation, accept, and join flows via RoomInvitationApiController (Correctness_Property 14)
8. THE Integration_Test SHALL cover the direct chat creation and listing flows via DirectChatApiController (Correctness_Property 20)
9. THE Integration_Test SHALL cover the file upload and download flows via AttachmentApiController, including access control for non-members (Correctness_Property 21)
10. THE Integration_Test SHALL cover the password change and password reset flows via PasswordApiController (Correctness_Properties 6, 7)
11. THE Integration_Test SHALL cover the account deletion flow via UserApiController, verifying cascade effects (Correctness_Property 8)
12. THE Integration_Test SHALL cover the session listing and termination flows via SessionApiController (Correctness_Property 5)
13. THE Integration_Test SHALL cover the web controller rendering: verify `/login`, `/register`, `/chat`, `/rooms/catalog`, `/profile` return HTTP 200 with correct Thymeleaf templates for authenticated users
14. THE Integration_Test SHALL verify that unauthenticated access to `/chat` redirects to `/login`

### Requirement 8: Integration Tests for Presence and Notification Flows

**User Story:** As a developer, I want integration tests for presence tracking and unread notification computation, so that Redis-backed real-time features are validated against real infrastructure.

#### Acceptance Criteria

1. THE Integration_Test SHALL verify that recording an active heartbeat changes user status to ONLINE (Correctness_Property 9)
2. THE Integration_Test SHALL verify that recording an inactive heartbeat changes user status to AFK (Correctness_Property 9)
3. THE Integration_Test SHALL verify that removing presence sets user status to OFFLINE (Correctness_Property 9)
4. THE Integration_Test SHALL verify that sending messages increments the unread count for non-viewing members (Correctness_Property 24)
5. THE Integration_Test SHALL verify that opening a room resets the unread count to zero (Correctness_Property 24)

### Requirement 9: Access Control and Authorization Tests

**User Story:** As a developer, I want tests that verify users cannot access unauthorized data, so that the application enforces proper data isolation between users.

#### Acceptance Criteria

1. THE Integration_Test SHALL verify that a non-member User requesting messages from a Room they are not in receives HTTP 403 (Correctness_Property 15)
2. THE Integration_Test SHALL verify that a non-member User requesting to download an Attachment from a Room they are not in receives HTTP 403 (Correctness_Property 21)
3. THE Integration_Test SHALL verify that a non-admin Member attempting to kick another Member receives HTTP 403 (Correctness_Property 16)
4. THE Integration_Test SHALL verify that a non-admin Member attempting to delete another User's Message receives HTTP 403 (Correctness_Property 16)
5. THE Integration_Test SHALL verify that a non-owner Member attempting to grant admin role receives HTTP 403 (Correctness_Property 16)
6. THE Integration_Test SHALL verify that a User attempting to edit another User's Message receives HTTP 403 (Correctness_Property 18)
7. THE Integration_Test SHALL verify that a User attempting to delete another User's Message (without admin role) receives HTTP 403 (Correctness_Property 19)
8. THE Integration_Test SHALL verify that a banned User attempting to join a Room receives HTTP 403 (Correctness_Property 14)
9. THE Integration_Test SHALL verify that a blocked User attempting to send a friend request to the blocker receives HTTP 403 (Correctness_Property 11)
10. THE Integration_Test SHALL verify that a blocked User attempting to send a message in a frozen Direct_Chat receives HTTP 403 (Correctness_Property 11)
11. THE Integration_Test SHALL verify that an unauthenticated request to any `/api/**` endpoint receives HTTP 401
12. THE Integration_Test SHALL verify that a non-invited User attempting to join a Private_Room receives HTTP 403 (Correctness_Property 14)

### Requirement 10: Multi-User Scenario Tests

**User Story:** As a developer, I want tests that exercise multi-user interactions, so that cross-user operations like friend requests, bans, room membership, and message visibility are verified end-to-end.

#### Acceptance Criteria

1. THE Integration_Test SHALL verify the full friend request flow between two Users: User A sends request, User B accepts, both see each other in friend list (Correctness_Property 10)
2. THE Integration_Test SHALL verify that when User A bans User B, User B can no longer send messages or friend requests to User A, and any existing Friendship is deleted (Correctness_Property 11)
3. THE Integration_Test SHALL verify that when User A bans User B and they have a Direct_Chat, the Direct_Chat becomes read-only for both participants (Correctness_Property 11)
4. THE Integration_Test SHALL verify that when an Admin kicks a Member from a Room, the Member can no longer access Room messages and a RoomBan is created (Correctness_Properties 15, 16)
5. THE Integration_Test SHALL verify that when a Room Owner deletes their account, all owned Rooms and their contents are cascade-deleted, and other Users' memberships in those Rooms are removed (Correctness_Property 8)
6. THE Integration_Test SHALL verify that two Users can exchange messages in a Room and both see each other's messages in the correct watermark order (Correctness_Properties 17, 22)
7. THE Integration_Test SHALL verify the private room invitation flow: Owner invites User, User accepts, User becomes Member and can send messages (Correctness_Property 14)
8. THE Integration_Test SHALL verify that when User A unbans User B, User B can send friend requests again but the previously deleted Friendship is not restored (Correctness_Property 11)

### Requirement 11: UI End-to-End Tests with Playwright

**User Story:** As a developer, I want Playwright-based UI tests that drive a real browser against the running application, so that user-facing workflows are validated end-to-end.

#### Acceptance Criteria

1. THE UI_Test SHALL use the Playwright MCP tools available in the workspace to drive browser interactions
2. THE UI_Test SHALL verify the registration flow: navigate to `/register`, fill in email, username, and password, submit the form, and verify redirect to the login page (validates Requirement 1)
3. THE UI_Test SHALL verify the login flow: navigate to `/login`, enter valid credentials, submit, and verify redirect to `/chat` (validates Requirement 2)
4. THE UI_Test SHALL verify the room creation flow: from the chat page, create a new public room and verify the room appears in the sidebar (validates Requirement 8)
5. THE UI_Test SHALL verify the messaging flow: enter a room, send a message, and verify the message appears in the message area (validates Requirement 13)
6. THE UI_Test SHALL verify the room catalog flow: navigate to the catalog, search for a room by name, and verify search results display correctly (validates Requirement 9)
7. THE UI_Test SHALL verify the logout flow: click logout and verify redirect to the login page (validates Requirement 2)
8. IF the application is not running when UI tests are executed, THEN THE UI_Test SHALL provide clear instructions for starting the application via `docker compose up`

### Requirement 12: WebSocket Handler Integration Tests

**User Story:** As a developer, I want integration tests for STOMP WebSocket handlers, so that real-time message delivery, presence heartbeats, and typing indicators are verified end-to-end.

#### Acceptance Criteria

1. THE Integration_Test SHALL verify that sending a STOMP message to `/app/chat.send` persists the message and broadcasts it to `/topic/room.{roomId}` subscribers
2. THE Integration_Test SHALL verify that sending a heartbeat to `/app/presence.heartbeat` with `active=true` updates the user's presence status in Redis
3. THE Integration_Test SHALL verify that sending a typing indicator to `/app/typing` broadcasts a RoomEvent to `/topic/room.{roomId}.events`
4. THE Integration_Test SHALL verify that a `@MessageExceptionHandler` in ChatMessageHandler sends error payloads to `/user/queue/errors` when message sending fails
5. THE Integration_Test SHALL verify that unauthenticated STOMP connections are rejected
6. THE Integration_Test SHALL verify that a message sent via WebSocket by User A in a Room is received by User B who is subscribed to that Room's topic

### Requirement 13: Input Validation and Error Handling Tests

**User Story:** As a developer, I want tests that verify input validation and error handling, so that invalid requests are rejected with correct HTTP status codes and error messages.

#### Acceptance Criteria

1. THE Integration_Test SHALL verify that sending a message with blank content returns HTTP 400
2. THE Integration_Test SHALL verify that sending a message exceeding 3072 UTF-8 bytes returns HTTP 400
3. THE Integration_Test SHALL verify that creating a room with a blank name returns HTTP 400
4. THE Integration_Test SHALL verify that creating a room with a name exceeding 100 characters returns HTTP 400
5. THE Integration_Test SHALL verify that requesting a password reset with an invalid email format returns HTTP 400
6. THE Integration_Test SHALL verify that accessing a non-existent room returns HTTP 404
7. THE Integration_Test SHALL verify that accessing a non-existent message returns HTTP 404
8. THE Integration_Test SHALL verify that accessing a non-existent friendship returns HTTP 404
9. THE Integration_Test SHALL verify that the GlobalApiExceptionHandler maps ConflictException to HTTP 409
10. THE Integration_Test SHALL verify that the GlobalApiExceptionHandler maps FileStorageException to HTTP 400
11. THE Integration_Test SHALL verify that malformed UUID path variables return HTTP 400

### Requirement 14: Edge Case and Idempotency Tests

**User Story:** As a developer, I want tests for edge cases including duplicate operations, self-actions, and boundary conditions, so that the application handles all corner cases correctly.

#### Acceptance Criteria

1. THE Unit_Test SHALL verify that joining a room the user is already a member of throws ConflictException
2. THE Unit_Test SHALL verify that sending a friend request to someone with an existing friendship throws ConflictException
3. THE Unit_Test SHALL verify that banning a user who is already banned throws ConflictException
4. THE Unit_Test SHALL verify that creating a direct chat that already exists returns the existing room (idempotent)
5. THE Unit_Test SHALL verify that a user cannot ban themselves (throws ConflictException)
6. THE Unit_Test SHALL verify that a user cannot send a friend request to themselves (throws ConflictException)
7. THE Unit_Test SHALL verify that a user cannot create a direct chat with themselves (throws ForbiddenException)
8. THE Unit_Test SHALL verify that the room owner cannot be kicked (throws ForbiddenException)
9. THE Unit_Test SHALL verify that only the friend request recipient can accept or decline (throws ForbiddenException for non-recipient)
10. THE Unit_Test SHALL verify that only friendship participants can remove the friendship (throws ForbiddenException for non-participant)
11. THE Integration_Test SHALL verify that CSRF tokens are required for POST/PUT/DELETE requests to `/api/**` endpoints
12. THE Integration_Test SHALL verify that public paths (`/login`, `/register`, `/api/health`) are accessible without authentication

### Requirement 15: Test Organization and Infrastructure

**User Story:** As a developer, I want a well-organized test suite with shared infrastructure, so that tests are maintainable and run efficiently.

#### Acceptance Criteria

1. THE Test_Suite SHALL organize unit tests in `src/test/java/com/bovae/yac/unit/` with one test class per service
2. THE Test_Suite SHALL organize parameterized tests in `src/test/java/com/bovae/yac/parameterized/` grouped by domain area
3. THE Test_Suite SHALL keep existing property-based tests in `src/test/java/com/bovae/yac/property/` unchanged
4. THE Test_Suite SHALL organize integration tests in `src/test/java/com/bovae/yac/integration/` grouped by API controller domain
5. THE Test_Suite SHALL organize UI test scripts in `src/test/e2e/` as documented Playwright workflows
6. THE Test_Suite SHALL reuse the existing TestcontainersConfig for all integration and parameterized tests that require database and Redis access
7. THE Test_Suite SHALL use Mockito for all unit test mocking, following the existing project dependency on `spring-boot-starter-webmvc-test`
8. THE Test_Suite SHALL ensure that each test class documents which Correctness_Properties and Requirements it validates via Javadoc comments
9. THE Test_Suite SHALL ensure all tests can be run via `mvn test` for unit and parameterized tests, and `mvn verify` for integration tests
10. THE Test_Suite SHALL ensure all existing property-based tests continue to pass after any code fixes
