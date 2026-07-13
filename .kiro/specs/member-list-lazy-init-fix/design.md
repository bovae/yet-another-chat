# Member List Lazy Initialization Fix — Bugfix Design

## Overview

Navigating to a room page with members causes a 500 error due to `org.hibernate.LazyInitializationException`. The `RoomMember.user` association is `FetchType.LAZY`, and `RoomMemberRepository.findByRoom()` returns entities with uninitialized `User` proxies. Since `spring.jpa.open-in-view` is `false`, the Hibernate session closes before Thymeleaf renders the `member-list-room` fragment, which accesses `member.user.id`, `member.user.displayName`, and `member.user.username`.

This bug is a symptom of a systemic issue: services return raw JPA entities to controllers and templates, allowing lazy proxies to leak past the persistence boundary. The same class of bug exists (or will exist) in `FriendshipService.listFriends()` (lazy `requester`/`recipient`), `DirectChatService.listDirectChats()` (lazy `owner`), `UserBanService.listBannedUsers()` (lazy `blocker`/`blocked`), and any other service method that returns entities with lazy associations.

The fix adopts a **DTO-first architecture across all services**: every service method that returns data to controllers or templates returns DTO records instead of JPA entities. MapStruct mappers convert entities to DTOs at the service layer. Four key design decisions govern the approach:

1. **All services return DTOs** — not just `RoomMemberService`. Every service that returns entities to controllers/templates gets a DTO record, a MapStruct mapper, and (where needed) a `JOIN FETCH` repository method. This prevents `LazyInitializationException` across the entire application.

2. **`@Transactional` rules — keep where needed, omit only for read-only DTO mapping**:
   - **Write/transactional service methods** (e.g., `joinPublicRoom`, `sendMessage`, `createRoom`, `deleteRoom`, `acceptFriendRequest`, `kickMember`, `banUser`, `getOrCreateDirectChat`) **KEEP their existing `@Transactional` annotations**. `@Transactional` is a critical part of the application for any method that performs writes or multi-step operations. DTO mapping for these methods happens inside the transaction — that is correct and expected.
   - **Read-only service methods that ONLY fetch and map to DTOs** (e.g., `listMembers`, `listFriends`, `listDirectChats`, `listBannedUsers`) do **NOT** get `@Transactional`. The repository call runs in Spring Data JPA's own short-lived transaction. After it returns, the session closes. MapStruct then maps the **detached** entities to DTOs. If `JOIN FETCH` is correct, mapping succeeds (the association is already initialized). If `JOIN FETCH` is missing, MapStruct fails with `LazyInitializationException` at the service layer — not at the template. This makes the fix **self-verifying**.
   - **Key principle**: "We don't need `@Transactional` only in places where we map Entities to DTOs." Everywhere else, `@Transactional` stays as-is.

3. **Separate repository methods** — instead of modifying existing derived queries like `findByRoom`, new methods like `findByRoomWithUsers` are created with `JOIN FETCH`. This keeps the original queries lean for callers that don't need the association data.

4. **Repository approach — `@Query` with JOIN FETCH, not custom repositories**:
   - All new fetch-join queries use `@Query` annotations on Spring Data JPA interface methods (e.g., `@Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room = :room")`). This is simple, declarative, and requires no boilerplate.
   - Custom repository implementations with `@PersistenceContext EntityManager` are **NOT** used. The queries are straightforward `JOIN FETCH` additions — `@Query` on the interface is sufficient.
   - Custom repos with `EntityManager` would be overkill for these simple queries. Reserve that pattern for future needs (dynamic queries, Criteria API, complex multi-table joins).

## Glossary

- **Bug_Condition (C)**: Calling any service method that returns JPA entities with uninitialized lazy proxies, then accessing those proxies outside an open Hibernate session — triggers `LazyInitializationException`
- **Property (P)**: Services return DTO records with all fields populated; accessing DTO fields outside the persistence context always succeeds
- **Preservation**: All existing behavior unrelated to service return types — join, leave, membership checks, moderation actions, message CRUD, friendship lifecycle, and all `FetchType.LAZY` annotations on entities — must remain unchanged
- **Session-absent mapping**: The deliberate pattern of mapping entities to DTOs after the Hibernate session has closed (no `@Transactional` on the service method), so that missing `JOIN FETCH` queries cause immediate failure at the service layer rather than silent proxy leakage. Applies ONLY to read-only methods that fetch and map to DTOs (e.g., `listMembers`, `listFriends`). Write/transactional methods (e.g., `joinPublicRoom`, `sendMessage`) keep their `@Transactional` — DTO mapping inside the transaction is correct for those methods
- **`RoomMemberDto`**: Java `record` with flattened member data (`userId`, `username`, `displayName`, `role`, `joinedAt`)
- **`FriendshipDto`**: Java `record` with flattened friendship data (`id`, `requesterId`, `requesterUsername`, `requesterDisplayName`, `recipientId`, `recipientUsername`, `recipientDisplayName`, `status`, `requestText`, `createdAt`)
- **`RoomDto`**: Java `record` with flattened room data (`id`, `name`, `description`, `visibility`, `ownerId`, `ownerUsername`, `nextWatermark`, `createdAt`)
- **`UserDto`**: Java `record` with safe user data (`id`, `email`, `username`, `displayName`, `createdAt`) — excludes `passwordHash`
- **`UserBanDto`**: Java `record` with flattened ban data (`id`, `blockedId`, `blockedUsername`, `blockedDisplayName`, `createdAt`)
- **`MessageDto`**: Java `record` with flattened message data (`id`, `roomId`, `senderId`, `senderUsername`, `content`, `replyToId`, `edited`, `watermark`, `createdAt`)
- **`DirectChatDto`**: Java `record` with flattened direct chat data (`id`, `name`, `otherUserId`, `otherUsername`, `otherDisplayName`, `createdAt`)
- **MapStruct Mapper**: A `@Mapper` interface that generates compile-time entity-to-DTO mapping code. One mapper per service/entity group. The `componentModel = "spring"` default is set globally via `maven-compiler-plugin` compiler arg (`-Amapstruct.defaultComponentModel=spring`), so individual mappers do not need to specify it.
- **`findByRoomWithUsers(Room)`**: Separate `@Query` method on `RoomMemberRepository` with `JOIN FETCH rm.user` — does not replace `findByRoom`. Uses `@Query` annotation on the Spring Data JPA interface (not a custom repository with `EntityManager`)
- **`open-in-view: false`**: Spring Boot JPA setting that closes the Hibernate session at the end of the `@Transactional` boundary (or immediately after the repository call if no transaction is active), rather than keeping it open through view rendering

## Bug Details

### Bug Condition

The bug manifests when a user navigates to a room page that has one or more members. `ChatWebController.roomView()` calls `RoomMemberService.listMembers(room)`, which delegates to `RoomMemberRepository.findByRoom(room)`. This query returns `RoomMember` entities with lazy `User` proxies. Since `listMembers()` has no `@Transactional` annotation and `open-in-view` is `false`, the Hibernate session closes immediately after the repository call. When Thymeleaf later renders `member-list-room` and accesses `member.user.id`, `member.user.displayName`, or `member.user.username`, Hibernate throws `LazyInitializationException`.

The same class of bug exists (latently or actively) in:
- `FriendshipApiController.listFriends()` — serializes `Friendship` entities with lazy `requester`/`recipient` User proxies
- `DirectChatApiController.listDirectChats()` — serializes `Room` entities with lazy `owner` User proxy
- `DirectChatApiController.createOrGetDirectChat()` — serializes `Room` entity with lazy `owner` (works by accident inside `@Transactional`)
- `UserBanService.listBannedUsers()` — returns `UserBan` entities with lazy `blocker`/`blocked` User proxies
- `RoomMemberApiController.listMembers()` — calls `toMemberResponse(member)` accessing `member.getUser().getId()` outside a transaction

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type { serviceMethod: String, returnType: String }
  OUTPUT: boolean

  entities := serviceMethod.invoke(input.args)
  RETURN entities IS NOT EMPTY
         AND hibernateSessionIsClosed()
         AND ANY entity IN entities: entity HAS uninitialized lazy proxy
         AND caller ACCESSES lazy proxy field
END FUNCTION
```

### Examples

- User navigates to `/chat/rooms/{id}` for a room with 3 members → `LazyInitializationException` when Thymeleaf accesses `member.user.displayName` on the first member
- `GET /api/rooms/{id}/members` for a room with 1 member → `LazyInitializationException` when `toMemberResponse` accesses `member.getUser().getId()`
- `GET /api/friends` for a user with 2 friends → `LazyInitializationException` when Jackson serializes `friendship.requester.username` (lazy proxy)
- `GET /api/direct-chats` for a user with 1 direct chat → `LazyInitializationException` when Jackson serializes `room.owner.username` (lazy proxy)
- User navigates to `/chat/rooms/{id}` for a room with 0 members → no exception (empty list, no proxy access) — NOT a bug condition
- `MessageService.sendMessage()` in a DIRECT room → calls `listMembers` inside `@Transactional`, session is open, no exception — works by coincidence of the transaction boundary

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- All `FetchType.LAZY` annotations on entity associations MUST remain unchanged — no switch to `EAGER`
- `RoomMemberService.joinPublicRoom()` must continue to create members successfully
- `RoomMemberService.joinPrivateRoomViaInvitation()` must continue to work for invite-based joins
- `RoomMemberService.leaveRoom()` must continue to remove members from rooms
- `RoomMemberService.isMember()` must continue to return correct boolean membership status
- `FriendshipService.areFriends()` must continue to return correct boolean friendship status
- `FriendshipService.sendFriendRequest/acceptFriendRequest/declineFriendRequest/removeFriend` must continue to work
- `RoomService.deleteRoom()` and `deleteRoomCascade()` must continue to cascade-delete correctly
- `ModerationService` kick/ban/unban/grantAdmin/revokeAdmin must continue to work
- `MessageService.sendMessage/editMessage/deleteMessage` must continue to work
- All existing repository derived queries (`findByRoom`, `findByUser`, `findByBlocker`, etc.) must remain unchanged — new `JOIN FETCH` methods are additive
- Empty collection queries must continue to return empty lists of DTOs

**Scope:**
All code paths that do NOT involve returning data to controllers/templates should be completely unaffected. This includes:
- All `existsBy*` boolean checks
- All `findById` calls used internally by services for write operations
- All delete operations
- All internal service-to-service calls that pass entities (e.g., `RoomService.getRoomById()` used to resolve a room for a subsequent service call)

## Hypothesized Root Cause

Based on the bug description and code analysis, the root cause is a systemic architectural issue:

1. **Missing fetch join on `findByRoom` query**: `RoomMemberRepository.findByRoom(Room)` is a Spring Data JPA derived query that generates `SELECT rm FROM RoomMember rm WHERE rm.room = :room`. This does not include a `JOIN FETCH` for the `user` association, so Hibernate returns `RoomMember` entities with uninitialized `User` proxies.

2. **No `@Transactional` on read-only service methods**: `RoomMemberService.listMembers()`, `FriendshipService.listFriends()`, `DirectChatService.listDirectChats()`, and `UserBanService.listBannedUsers()` have no `@Transactional` annotation. Spring Data JPA wraps each repository call in its own short-lived transaction, which closes immediately after the query returns. All lazy proxies become detached at that point.

3. **`open-in-view: false` closes session early**: With this setting, there is no view-scoped session to fall back on. The session is strictly bounded to the transaction, so any lazy access after the repository call fails.

4. **Entities leak to controllers and templates**: Services return raw JPA entities (`RoomMember`, `Friendship`, `Room`, `UserBan`) which carry JPA proxy behavior. Controllers, Thymeleaf templates, and Jackson serialization access lazy proxy fields on these detached entities, triggering the exception. Returning DTOs instead of entities eliminates this class of bug entirely.

5. **Some paths work by accident**: `MessageService.checkDirectChatBan()` calls `listMembers` inside a `@Transactional` method, so the session is open and lazy access works. `DirectChatService.getOrCreateDirectChat()` is `@Transactional`, so `room.owner` is accessible. These paths work by coincidence of the transaction boundary, not by design.

## Correctness Properties

Property 1: Bug Condition — DTO Fields Populated After Service Call (Session Absent)

_For any_ service method that returns DTOs (e.g., `listMembers`, `listFriends`, `listDirectChats`, `listBannedUsers`), calling the method and then accessing all DTO fields outside an open Hibernate session SHALL succeed without throwing `LazyInitializationException`, and the returned data SHALL match the persisted entities.

**Validates: Requirements 2.1, 2.2, 2.3**

Property 2: Self-Verifying Mapping — Missing JOIN FETCH Causes Immediate Failure

_For any_ read-only service method that maps entities to DTOs without `@Transactional`, if the underlying repository query does NOT include `JOIN FETCH` for a required lazy association, the MapStruct mapping step SHALL throw `LazyInitializationException` at the service layer (not at the controller/template layer), providing immediate feedback that the fetch join is missing.

**Validates: Requirements 2.3, 2.4**

Property 3: Preservation — Non-DTO Code Paths Unchanged

_For any_ operation that does NOT involve returning data to controllers/templates (join, leave, isMember, areFriends, moderation actions, message CRUD, delete cascades, boolean checks), the fixed code SHALL produce exactly the same result as the original code, preserving all existing functionality.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10**

Property 4: Separate Repository Methods — Original Queries Unchanged

_For any_ existing repository derived query (`findByRoom`, `findByUser`, `findByBlocker`, etc.), the query behavior and generated SQL SHALL remain identical after the fix. New `JOIN FETCH` methods are additive and do not modify existing methods.

**Validates: Requirements 2.4, 3.10**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**1. Add MapStruct dependency to `pom.xml`**

Add `mapstruct` and `mapstruct-processor` dependencies. The processor must be added to the `maven-compiler-plugin` annotation processor paths alongside Lombok (order matters: Lombok first, then MapStruct, so MapStruct sees Lombok-generated getters).

- `org.mapstruct:mapstruct` (compile scope)
- `org.mapstruct:mapstruct-processor` (annotation processor)
- `lombok-mapstruct-binding` (annotation processor, enables Lombok + MapStruct interop)
- Add compiler arg `-Amapstruct.defaultComponentModel=spring` to `maven-compiler-plugin` — this sets the default component model globally, so individual `@Mapper` interfaces do not need `componentModel = "spring"`

---

### Service-by-Service Changes

#### 2. RoomMemberService (the original bug)

**DTO**: `src/main/java/com/bovae/yac/model/dto/RoomMemberDto.java`

```java
public record RoomMemberDto(
    UUID userId,
    String username,
    String displayName,
    RoomRole role,
    Instant joinedAt
) {}
```

**Mapper**: `src/main/java/com/bovae/yac/model/dto/RoomMemberMapper.java`

A `@Mapper` interface mapping `RoomMember` → `RoomMemberDto` (Spring component model is set globally via compiler arg):
- `@Mapping(source = "user.id", target = "userId")`
- `@Mapping(source = "user.username", target = "username")`
- `@Mapping(source = "user.displayName", target = "displayName")`

**Repository**: `src/main/java/com/bovae/yac/repository/RoomMemberRepository.java`

Add a **separate** method — do NOT modify `findByRoom`:
```java
@Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room = :room")
List<RoomMember> findByRoomWithUsers(@Param("room") Room room);
```

**Service**: `src/main/java/com/bovae/yac/service/RoomMemberService.java`

- Inject `RoomMemberMapper` via constructor
- Change `listMembers()` return type from `List<RoomMember>` to `List<RoomMemberDto>`
- Call `findByRoomWithUsers(room)` instead of `findByRoom(room)`
- Map via `roomMemberMapper.toDto(member)` for each entity
- **Do NOT add `@Transactional`** — the repository call runs in Spring Data JPA's short-lived transaction, session closes, then MapStruct maps detached entities. If `JOIN FETCH` is correct, `user` is already initialized and mapping succeeds. If `JOIN FETCH` is missing, mapping fails immediately with `LazyInitializationException`.

**Controllers**:
- `ChatWebController.roomView()`: change `List<RoomMember>` to `List<RoomMemberDto>`
- `RoomMemberApiController.listMembers()`: map from `RoomMemberDto` instead of `RoomMember` entity (or use `RoomMemberDto` directly as the response, removing the `MemberResponse` inner record since fields match)

**Template**: `src/main/resources/templates/fragments/member-list.html`

Replace entity property paths with DTO field names:
- `member.user.id` → `member.userId`
- `member.user.displayName` → `member.displayName`
- `member.user.username` → `member.username`
- `member.role` stays the same

**Downstream consumer**: `MessageService.checkDirectChatBan()`

Update to use DTO fields:
- `member.getUser().getId()` → `member.userId()`
- For the ban check, use `member.userId()` to load the `User` entity from `UserRepository`, OR refactor `UserBanService.isBanExistsBetween` to accept `UUID` user IDs instead of `User` entities

---

#### 3. FriendshipService

**DTO**: `src/main/java/com/bovae/yac/model/dto/FriendshipDto.java`

```java
public record FriendshipDto(
    UUID id,
    UUID requesterId,
    String requesterUsername,
    String requesterDisplayName,
    UUID recipientId,
    String recipientUsername,
    String recipientDisplayName,
    FriendshipStatus status,
    String requestText,
    Instant createdAt
) {}
```

**Mapper**: `src/main/java/com/bovae/yac/model/dto/FriendshipMapper.java`

A `@Mapper` interface mapping `Friendship` → `FriendshipDto` (Spring component model is set globally via compiler arg):
- `@Mapping(source = "requester.id", target = "requesterId")`
- `@Mapping(source = "requester.username", target = "requesterUsername")`
- `@Mapping(source = "requester.displayName", target = "requesterDisplayName")`
- `@Mapping(source = "recipient.id", target = "recipientId")`
- `@Mapping(source = "recipient.username", target = "recipientUsername")`
- `@Mapping(source = "recipient.displayName", target = "recipientDisplayName")`

**Repository**: `src/main/java/com/bovae/yac/repository/FriendshipRepository.java`

Add separate `JOIN FETCH` methods:
```java
@Query("SELECT f FROM Friendship f JOIN FETCH f.requester JOIN FETCH f.recipient WHERE f.requester = :user AND f.status = :status")
List<Friendship> findByRequesterAndStatusWithUsers(@Param("user") User user, @Param("status") FriendshipStatus status);

@Query("SELECT f FROM Friendship f JOIN FETCH f.requester JOIN FETCH f.recipient WHERE f.recipient = :user AND f.status = :status")
List<Friendship> findByRecipientAndStatusWithUsers(@Param("user") User user, @Param("status") FriendshipStatus status);
```

**Service**: `src/main/java/com/bovae/yac/service/FriendshipService.java`

- Inject `FriendshipMapper` via constructor
- Change `listFriends()` return type from `List<Friendship>` to `List<FriendshipDto>`
- Use `findByRequesterAndStatusWithUsers` and `findByRecipientAndStatusWithUsers` instead of the non-fetch-join variants
- Map via `friendshipMapper.toDto(friendship)` for each entity
- **Do NOT add `@Transactional`** — session-absent mapping applies

**Controller**: `FriendshipApiController.listFriends()`

- Change `ResponseEntity<List<Friendship>>` to `ResponseEntity<List<FriendshipDto>>`

---

#### 4. DirectChatService

**DTO**: `src/main/java/com/bovae/yac/model/dto/DirectChatDto.java`

```java
public record DirectChatDto(
    UUID id,
    String name,
    UUID otherUserId,
    String otherUsername,
    String otherDisplayName,
    Instant createdAt
) {}
```

Note: `DirectChatDto` is specific to the direct chat list view — it includes the "other" user's info relative to the requesting user, which is more useful than a generic `RoomDto` for this endpoint.

**DTO**: `src/main/java/com/bovae/yac/model/dto/RoomDto.java`

```java
public record RoomDto(
    UUID id,
    String name,
    String description,
    RoomVisibility visibility,
    UUID ownerId,
    String ownerUsername,
    Long nextWatermark,
    Instant createdAt
) {}
```

**Mapper**: `src/main/java/com/bovae/yac/model/dto/RoomMapper.java`

A `@Mapper` interface mapping `Room` → `RoomDto` (Spring component model is set globally via compiler arg):
- `@Mapping(source = "owner.id", target = "ownerId")`
- `@Mapping(source = "owner.username", target = "ownerUsername")`

**Repository**: `src/main/java/com/bovae/yac/repository/RoomMemberRepository.java`

Add a separate method for loading members with their rooms and room owners:
```java
@Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.room r JOIN FETCH r.owner WHERE rm.user = :user")
List<RoomMember> findByUserWithRoomAndOwner(@Param("user") User user);
```

**Service**: `src/main/java/com/bovae/yac/service/DirectChatService.java`

- Inject `RoomMapper` via constructor
- Change `listDirectChats()` return type from `List<Room>` to `List<DirectChatDto>`
- Use `findByUserWithRoomAndOwner` to load memberships with rooms and owners
- Filter for DIRECT visibility, then build `DirectChatDto` with the other user's info
- Change `getOrCreateDirectChat()` return type from `Room` to `RoomDto` — map via `roomMapper.toDto(room)` (this method is already `@Transactional`, so mapping happens inside the session — acceptable for write methods)

**Controller**: `DirectChatApiController`

- Change `ResponseEntity<Room>` to `ResponseEntity<RoomDto>` for `createOrGetDirectChat`
- Change `ResponseEntity<List<Room>>` to `ResponseEntity<List<DirectChatDto>>` for `listDirectChats`

---

#### 5. UserBanService

**DTO**: `src/main/java/com/bovae/yac/model/dto/UserBanDto.java`

```java
public record UserBanDto(
    UUID id,
    UUID blockedId,
    String blockedUsername,
    String blockedDisplayName,
    Instant createdAt
) {}
```

Note: The DTO only includes the blocked user's info (not the blocker's), since `listBannedUsers` is always called for the current user (the blocker).

**Mapper**: `src/main/java/com/bovae/yac/model/dto/UserBanMapper.java`

A `@Mapper` interface mapping `UserBan` → `UserBanDto` (Spring component model is set globally via compiler arg):
- `@Mapping(source = "blocked.id", target = "blockedId")`
- `@Mapping(source = "blocked.username", target = "blockedUsername")`
- `@Mapping(source = "blocked.displayName", target = "blockedDisplayName")`

**Repository**: `src/main/java/com/bovae/yac/repository/UserBanRepository.java`

Add a separate method:
```java
@Query("SELECT ub FROM UserBan ub JOIN FETCH ub.blocked WHERE ub.blocker = :blocker")
List<UserBan> findByBlockerWithBlocked(@Param("blocker") User blocker);
```

**Service**: `src/main/java/com/bovae/yac/service/UserBanService.java`

- Inject `UserBanMapper` via constructor
- Change `listBannedUsers()` return type from `List<UserBan>` to `List<UserBanDto>`
- Use `findByBlockerWithBlocked` instead of `findByBlocker`
- Map via `userBanMapper.toDto(ban)` for each entity
- **Do NOT add `@Transactional`** — session-absent mapping applies

---

#### 6. UserService

**DTO**: `src/main/java/com/bovae/yac/model/dto/UserDto.java`

```java
public record UserDto(
    UUID id,
    String email,
    String username,
    String displayName,
    Instant createdAt
) {}
```

Note: `User` has no lazy associations (all fields are columns), so `LazyInitializationException` is not a risk. However, returning `UserDto` instead of `User` prevents `passwordHash` from leaking to controllers/templates and establishes the DTO-first pattern consistently.

**Mapper**: `src/main/java/com/bovae/yac/model/dto/UserMapper.java`

A `@Mapper` interface mapping `User` → `UserDto` (Spring component model is set globally via compiler arg). No `@Mapping` annotations needed — field names match directly. MapStruct will ignore `passwordHash` since it has no corresponding DTO field.

**Service**: `src/main/java/com/bovae/yac/service/UserService.java`

- Inject `UserMapper` via constructor
- Change `register()` return type from `User` to `UserDto`
- Change `updateProfile()` return type from `User` to `UserDto`
- Change `getById()` return type from `User` to `UserDto`
- Change `findByUsername()` return type from `Optional<User>` to `Optional<UserDto>`
- No `JOIN FETCH` needed — `User` has no lazy associations

Note: Internal callers that need the `User` entity (e.g., controllers resolving the current user via `userRepository.findByEmail()`) continue to use the repository directly. The DTO conversion applies to service methods that return user data to controllers/templates.

---

#### 7. RoomService

**Service**: `src/main/java/com/bovae/yac/service/RoomService.java`

- Inject `RoomMapper` via constructor
- `createRoom()` already runs in `@Transactional` — change return type from `Room` to `RoomDto`, map via `roomMapper.toDto(room)` inside the transaction
- `getRoomById()` is used both internally (to resolve a room for subsequent service calls) and by controllers. Split into two methods:
  - `getRoomById(UUID)` — returns `Room` entity for internal use (unchanged)
  - `getRoomDtoById(UUID)` — returns `RoomDto` for controller use, using a `JOIN FETCH` query
- `searchCatalog()` already returns `Page<RoomCatalogEntry>` (DTO) — no change needed

**Repository**: `src/main/java/com/bovae/yac/repository/RoomRepository.java`

Add a separate method:
```java
@Query("SELECT r FROM Room r JOIN FETCH r.owner WHERE r.id = :id")
Optional<Room> findByIdWithOwner(@Param("id") UUID id);
```

---

#### 8. MessageService

**Service**: `src/main/java/com/bovae/yac/service/MessageService.java`

- `getMessageHistory()` already returns `MessagePage` containing `ChatMessageResponse` DTOs — no change needed
- `sendMessage()` and `editMessage()` return `Message` entities to WebSocket handlers. These methods are `@Transactional`, so the session is open during the return. The WebSocket handlers (`ChatMessageHandler`) should convert to `ChatMessageResponse` DTOs before broadcasting. If they already do this conversion inside the handler, the entity return is acceptable since the handler runs within the service's transaction scope.
- `checkDirectChatBan()` — update to use `RoomMemberDto` fields (see RoomMemberService section above)

Note: If `ChatMessageHandler` accesses lazy associations on the returned `Message` entity outside the transaction, it should be updated to accept `ChatMessageResponse` DTOs from the service instead. This is a lower-priority change since the `@Transactional` boundary currently covers the access.

---

#### 9. ModerationService

`ModerationService` methods all return `void` — no entity leakage to controllers. No DTO changes needed. The service uses `RoomMemberRepository.findById()` internally for write operations within `@Transactional` methods, which is correct.

---

#### 10. NotificationService

`NotificationService` methods return `void` or `int` — no entity leakage. Already uses `NotificationEvent` DTO for WebSocket broadcasts. No changes needed.

---

#### 11. PresenceService, PasswordService, AuthService, FileStorageService

These services either return primitives, enums, void, or already use DTOs. No changes needed.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior. The session-absent mapping pattern is central to the testing strategy — tests verify that DTO mapping succeeds on detached entities.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write integration tests that persist entities with lazy associations, call service methods (which return entities on unfixed code), clear the entity manager, then access lazy proxy fields. Run on UNFIXED code to observe `LazyInitializationException`.

**Test Cases**:
1. **RoomMember Detached Access Test**: Persist a room with 2 members, call `listMembers`, clear entity manager, access `member.getUser().getUsername()` (will throw `LazyInitializationException` on unfixed code)
2. **Friendship Detached Access Test**: Persist 2 users with an ACCEPTED friendship, call `listFriends`, clear entity manager, access `friendship.getRequester().getUsername()` (will throw on unfixed code)
3. **DirectChat Detached Access Test**: Persist a direct chat room, call `listDirectChats`, clear entity manager, access `room.getOwner().getUsername()` (will throw on unfixed code)
4. **UserBan Detached Access Test**: Persist a user ban, call `listBannedUsers`, clear entity manager, access `ban.getBlocked().getUsername()` (will throw on unfixed code)
5. **API Controller Path Test**: Call `GET /api/rooms/{id}/members` via MockMvc for a room with members (will return 500 on unfixed code)
6. **Friendship API Path Test**: Call `GET /api/friends` via MockMvc for a user with friends (will return 500 on unfixed code due to Jackson serializing lazy proxies)

**Expected Counterexamples**:
- `LazyInitializationException: Could not initialize proxy [com.bovae.yac.model.entity.User#<uuid>] - no Session`
- Root cause confirmed: derived queries generate SQL without JOIN on related tables

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FUNCTION expectedBehavior(result)
  INPUT: result of type List<? extends Record>  // any DTO
  OUTPUT: boolean

  FOR EACH dto IN result DO
    FOR EACH field IN dto.fields() DO
      ASSERT field IS accessible without LazyInitializationException
      IF field IS required THEN ASSERT field IS NOT NULL
    END FOR
  END FOR
  RETURN true
END FUNCTION

// RoomMemberService
FOR ALL room WHERE roomHasMembers(room) DO
  dtos := roomMemberService.listMembers(room)
  entityManager.clear()  // proves DTOs are independent of session
  ASSERT expectedBehavior(dtos)
  ASSERT dtos.size() == countMembersInRoom(room)
END FOR

// FriendshipService
FOR ALL user WHERE userHasFriends(user) DO
  dtos := friendshipService.listFriends(user)
  entityManager.clear()
  ASSERT expectedBehavior(dtos)
END FOR

// DirectChatService
FOR ALL user WHERE userHasDirectChats(user) DO
  dtos := directChatService.listDirectChats(user)
  entityManager.clear()
  ASSERT expectedBehavior(dtos)
END FOR

// UserBanService
FOR ALL user WHERE userHasBans(user) DO
  dtos := userBanService.listBannedUsers(user)
  entityManager.clear()
  ASSERT expectedBehavior(dtos)
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL room, user WHERE NOT isBugCondition(room) DO
  ASSERT joinPublicRoom(room, user) behaves identically
  ASSERT leaveRoom(room, user) behaves identically
  ASSERT isMember(room, user) returns same boolean
  ASSERT existsByRoomAndUser(room, user) returns same boolean
END FOR

FOR ALL userA, userB DO
  ASSERT areFriends(userA, userB) returns same boolean
  ASSERT isBanExistsBetween(userA, userB) returns same boolean
END FOR

FOR ALL room, actingUser, targetUser DO
  ASSERT kickMember(room, actingUser, targetUser) behaves identically
  ASSERT grantAdminRole(room, actingUser, targetUser) behaves identically
  ASSERT revokeAdminRole(room, actingUser, targetUser) behaves identically
END FOR
```

**Testing Approach**: Property-based testing (jqwik) is recommended for preservation checking because:
- It generates many entity combinations automatically across the input domain
- It catches edge cases that manual unit tests might miss (e.g., users with null displayName, rooms with special characters)
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for join, leave, isMember, areFriends, and moderation operations, then write property-based tests capturing that behavior persists after the fix.

**Test Cases**:
1. **Join Preservation**: Verify `joinPublicRoom` continues to create members correctly after the service/repository changes
2. **Leave Preservation**: Verify `leaveRoom` continues to remove members correctly
3. **Membership Check Preservation**: Verify `isMember` returns correct booleans for member and non-member users
4. **Friendship Check Preservation**: Verify `areFriends` returns correct booleans
5. **Empty Collection Preservation**: Verify all `list*` methods return empty DTO lists for entities with no related data
6. **checkDirectChatBan Preservation**: Verify `MessageService.checkDirectChatBan()` continues to correctly detect bans using DTO fields
7. **Moderation Preservation**: Verify kick, ban, unban, grantAdmin, revokeAdmin continue to work
8. **Original Query Preservation**: Verify `findByRoom`, `findByUser`, `findByBlocker` still return correct results (not affected by new `JOIN FETCH` methods)

### Unit Tests

- Test that each MapStruct mapper correctly maps all fields from entity to DTO:
  - `RoomMemberMapper.toDto()` — userId, username, displayName, role, joinedAt
  - `FriendshipMapper.toDto()` — all requester/recipient fields, status, requestText
  - `RoomMapper.toDto()` — ownerId, ownerUsername, all room fields
  - `UserMapper.toDto()` — all fields except passwordHash
  - `UserBanMapper.toDto()` — blockedId, blockedUsername, blockedDisplayName
- Test that mappers handle null `displayName` correctly (maps to null in DTO)
- Test that `UserMapper` does NOT map `passwordHash` to any DTO field
- Test that each `list*` method returns empty list of DTOs for empty collections
- Test that returned DTO data matches persisted entity data for each service

### Property-Based Tests

- Generate random room configurations (1–20 members with varied roles) and verify all returned `RoomMemberDto` records have non-null `userId`, `username`, `role`, and `joinedAt` fields, and that DTO count matches member count
- Generate random user profiles (with/without displayName) and verify mapped DTO `displayName` matches entity's `displayName` for all combinations
- Generate random friendship networks and verify `FriendshipDto` records contain correct requester/recipient data
- Generate random room/user pairs and verify `isMember`, `existsByRoomAndUser`, `areFriends`, and `isBanExistsBetween` produce identical results before and after the fix
- Generate random direct chat configurations and verify `DirectChatDto` records contain correct other-user data

### Integration Tests

- Full MockMvc test: `GET /api/rooms/{id}/members` returns 200 with correct member data
- Full MockMvc test: `GET /chat/rooms/{id}` renders the member list fragment without 500 error
- Full MockMvc test: `GET /api/friends` returns 200 with correct friendship data (no Jackson serialization error on lazy proxies)
- Full MockMvc test: `GET /api/direct-chats` returns 200 with correct direct chat data
- Test that `MessageService.checkDirectChatBan()` continues to work correctly using DTO fields
- Test that all MapStruct mappers are Spring beans and injectable via constructor injection
- Test session-absent mapping: call `listMembers` without `@Transactional`, verify DTOs are fully populated (proves `JOIN FETCH` works and mapping succeeds on detached entities)
