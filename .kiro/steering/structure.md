# Project Structure

## Root

```
├── pom.xml                          # Maven build (Spring Boot 4.0.5, Java 21)
├── Makefile                         # Build/run/test shortcuts
├── Dockerfile                       # Multi-stage: Corretto 21 build → Corretto 21-alpine runtime
├── docker-compose.yml               # App + PostgreSQL 17 + Redis 7
├── lombok.config                    # LOG field name, copyable annotations
├── src/main/java/com/bovae/yac/    # Application source
├── src/main/resources/              # Config, templates, migrations, static assets
└── src/test/java/com/bovae/yac/    # Tests
```

## Application Source (`src/main/java/com/bovae/yac/`)

```
├── YacApplication.java                          # @SpringBootApplication, @EnableScheduling
├── config/
│   ├── SecurityConfig.java                      # Form login, BCrypt, CSRF, remember-me
│   ├── WebSocketConfig.java                     # STOMP: /ws endpoint, /app prefix, /topic + /queue
│   ├── WebSocketSecurityConfig.java             # WebSocket auth
│   ├── MessageSecurityConfig.java               # WebSocket message-level security
│   ├── SessionConfig.java                       # @EnableRedisIndexedHttpSession
│   ├── WebMvcConfig.java                        # Static resources, WebJar locator
│   └── properties/                              # @ConfigurationProperties POJOs
├── controller/
│   ├── api/                                     # REST controllers → ResponseEntity<T>
│   │   ├── RoomApiController.java               # /api/rooms (CRUD, join, leave)
│   │   ├── MessageApiController.java            # /api/rooms/{roomId}/messages (CRUD, pagination)
│   │   ├── FriendshipApiController.java         # /api/friends (request, accept, decline, list, remove)
│   │   ├── RoomMemberApiController.java         # /api/rooms/{roomId}/members
│   │   ├── RoomBanApiController.java            # /api/rooms/{roomId}/bans
│   │   ├── RoomInvitationApiController.java     # /api/rooms/{roomId}/invitations
│   │   ├── DirectChatApiController.java         # /api/direct-chats
│   │   ├── AttachmentApiController.java         # /api/rooms/{roomId}/messages/{msgId}/attachments
│   │   ├── UserApiController.java               # /api/users (profile, account deletion)
│   │   ├── UserBanApiController.java            # /api/users/bans
│   │   ├── PasswordApiController.java           # /api/password (reset, change)
│   │   ├── SessionApiController.java            # /api/sessions (list, terminate)
│   │   └── HealthApiController.java             # /api/health
│   └── web/                                     # Thymeleaf page controllers → String view names
│       ├── HomeWebController.java               # / → redirect to /chat or /login
│       ├── AuthWebController.java               # /login, /register, /forgot-password
│       ├── ChatWebController.java               # /chat (main chat page)
│       ├── ProfileWebController.java            # /profile, /sessions
│       └── RoomWebController.java               # /rooms (catalog browsing)
├── service/                                     # Business logic (13 services)
│   ├── UserService.java                         # Register, update profile, delete account
│   ├── AuthService.java                         # UserDetailsService, session management
│   ├── PasswordService.java                     # Reset tokens, password change
│   ├── RoomService.java                         # Room CRUD, catalog search, cascade delete
│   ├── RoomMemberService.java                   # Join, leave, list members
│   ├── MessageService.java                      # Send, edit, delete, paginated history
│   ├── ModerationService.java                   # Kick, grant/revoke admin, delete messages
│   ├── FriendshipService.java                   # Friend request lifecycle
│   ├── UserBanService.java                      # Block/unblock users
│   ├── DirectChatService.java                   # Get-or-create direct chat rooms
│   ├── FileStorageService.java                  # Upload, download, delete files
│   ├── PresenceService.java                     # Heartbeat tracking, status computation
│   └── NotificationService.java                 # Unread counts, broadcast notifications
├── model/
│   ├── entity/                                  # 14 JPA entities (UUID PKs, Lombok @Data + @Builder)
│   ├── dto/                                     # Records + @Data classes for request/response
│   └── enums/                                   # RoomVisibility, RoomRole, FriendshipStatus, PresenceStatus
├── repository/                                  # 11 Spring Data JPA repositories
├── exception/                                   # Custom RuntimeExceptions + @ControllerAdvice handlers
│   ├── ResourceNotFoundException.java           # → 404
│   ├── ForbiddenException.java                  # → 403
│   ├── ConflictException.java                   # → 409
│   ├── FileStorageException.java                # → 400
│   ├── GlobalApiExceptionHandler.java           # @RestControllerAdvice for /api/**
│   └── GlobalWebExceptionHandler.java           # @ControllerAdvice for web views
├── validation/                                  # Custom Jakarta validators
│   ├── MaxByteSize.java / MaxByteSizeValidator  # UTF-8 byte length validation
│   └── UUID.java / UUIDValidator                # UUID format validation
└── ws/                                          # WebSocket STOMP handlers
    ├── ChatMessageHandler.java                  # /app/chat.send, /app/chat.edit, /app/chat.delete
    ├── PresenceHandler.java                     # /app/presence.heartbeat
    └── TypingHandler.java                       # /app/typing.start, /app/typing.stop
```

## Resources (`src/main/resources/`)

```
├── application.yml                  # Main config (env-var driven, sensible local defaults)
├── application-dev.yml              # Dev profile overrides
├── db/changelog/
│   ├── db.changelog-master.yaml     # Liquibase master changelog
│   └── 001-init-schema.sql          # Initial schema (all 14 tables)
├── templates/                       # Thymeleaf templates
│   ├── layout/                      # Base layouts
│   ├── fragments/                   # Reusable fragments
│   ├── auth/                        # Login, register, forgot-password
│   ├── chat/                        # Main chat view
│   ├── rooms/                       # Room catalog
│   ├── profile/                     # User profile, sessions
│   └── error/                       # Error pages
└── static/
    ├── css/                         # Custom stylesheets
    └── js/                          # Custom JavaScript (STOMP client, HTMX handlers)
```

## Tests (`src/test/java/com/bovae/yac/`)

```
├── YacApplicationTest.java          # Context loads smoke test
├── config/
│   └── TestcontainersConfig.java    # Shared PostgreSQL 17 + Redis 7 containers
├── controller/api/                  # Controller-level tests (planned)
├── integration/                     # Full-stack integration tests (MockMvc + Testcontainers)
│   ├── RestApiIntegrationTest.java  # Message CRUD, room CRUD, friendship, pagination
│   └── PresenceNotificationIntegrationTest.java
└── property/                        # Property-based tests (jqwik, 14 files)
```
