# Member List Lazy Initialization Fix — Tasks

## Task 1: Add MapStruct dependency to pom.xml

- [x] 1.1 Add `org.mapstruct:mapstruct` compile dependency (use property `<mapstruct.version>1.6.3</mapstruct.version>`)
- [x] 1.2 Add `org.mapstruct:mapstruct-processor` and `lombok-mapstruct-binding` to `maven-compiler-plugin` annotation processor paths (order: Lombok first, then lombok-mapstruct-binding, then MapStruct processor). Add compiler arg `-Amapstruct.defaultComponentModel=spring` to set Spring component model globally for all mappers
- [x] 1.3 Verify the project compiles with `./mvnw compile`

## Task 2: RoomMemberService DTO conversion (original bug fix)

- [x] 2.1 Create `RoomMemberDto` record in `src/main/java/com/bovae/yac/model/dto/RoomMemberDto.java` with fields: `UUID userId`, `String username`, `String displayName`, `RoomRole role`, `Instant joinedAt`
- [x] 2.2 Create `RoomMemberMapper` interface in `src/main/java/com/bovae/yac/model/dto/RoomMemberMapper.java` — `@Mapper` (no `componentModel` needed, set globally) with `@Mapping` annotations for `user.id → userId`, `user.username → username`, `user.displayName → displayName`; include `List<RoomMemberDto> toDtoList(List<RoomMember> members)` method
- [x] 2.3 Add `findByRoomWithUsers` method to `RoomMemberRepository` — `@Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room = :room")` returning `List<RoomMember>`
- [x] 2.4 Update `RoomMemberService.listMembers()` — inject `RoomMemberMapper`, change return type to `List<RoomMemberDto>`, call `findByRoomWithUsers(room)`, map via `roomMemberMapper.toDtoList(...)`. Do NOT add `@Transactional` (session-absent mapping)
- [x] 2.5 Update `ChatWebController.roomView()` — change `List<RoomMember>` to `List<RoomMemberDto>`, remove `RoomMember` import, add `RoomMemberDto` import
- [x] 2.6 Update `RoomMemberApiController.listMembers()` — use `RoomMemberDto` directly as the response type (fields match `MemberResponse`), remove the `toMemberResponse` mapping method and `MemberResponse` inner record (or keep `MemberResponse` and map from DTO)
- [x] 2.7 Update `member-list.html` template — replace `member.user.id` → `member.userId`, `member.user.displayName` → `member.displayName`, `member.user.username` → `member.username`
- [x] 2.8 Update `MessageService.checkDirectChatBan()` — change `List<RoomMember>` to `List<RoomMemberDto>`, replace `member.getUser().getId()` → `member.userId()`, load `User` entity from `UserRepository` for the `isBanExistsBetween` call (or refactor `isBanExistsBetween` to accept UUIDs)
- [x] 2.9 Verify compilation with `./mvnw compile`

## Task 3: FriendshipService DTO conversion

- [x] 3.1 Create `FriendshipDto` record in `src/main/java/com/bovae/yac/model/dto/FriendshipDto.java` with fields: `UUID id`, `UUID requesterId`, `String requesterUsername`, `String requesterDisplayName`, `UUID recipientId`, `String recipientUsername`, `String recipientDisplayName`, `FriendshipStatus status`, `String requestText`, `Instant createdAt`
- [x] 3.2 Create `FriendshipMapper` interface in `src/main/java/com/bovae/yac/model/dto/FriendshipMapper.java` — `@Mapper` (no `componentModel` needed, set globally) with `@Mapping` annotations for requester/recipient fields; include `List<FriendshipDto> toDtoList(List<Friendship> friendships)` method
- [x] 3.3 Add `findByRequesterAndStatusWithUsers` and `findByRecipientAndStatusWithUsers` methods to `FriendshipRepository` — `@Query` with `JOIN FETCH f.requester JOIN FETCH f.recipient`
- [x] 3.4 Update `FriendshipService.listFriends()` — inject `FriendshipMapper`, change return type to `List<FriendshipDto>`, use the new `WithUsers` repository methods, map via `friendshipMapper.toDtoList(...)`. Do NOT add `@Transactional` (session-absent mapping)
- [x] 3.5 Update `FriendshipApiController.listFriends()` — change `ResponseEntity<List<Friendship>>` to `ResponseEntity<List<FriendshipDto>>`
- [x] 3.6 Verify compilation with `./mvnw compile`

## Task 4: DirectChatService DTO conversion

- [x] 4.1 Create `DirectChatDto` record in `src/main/java/com/bovae/yac/model/dto/DirectChatDto.java` with fields: `UUID id`, `String name`, `UUID otherUserId`, `String otherUsername`, `String otherDisplayName`, `Instant createdAt`
- [x] 4.2 Create `RoomDto` record in `src/main/java/com/bovae/yac/model/dto/RoomDto.java` with fields: `UUID id`, `String name`, `String description`, `RoomVisibility visibility`, `UUID ownerId`, `String ownerUsername`, `Long nextWatermark`, `Instant createdAt`
- [x] 4.3 Create `RoomMapper` interface in `src/main/java/com/bovae/yac/model/dto/RoomMapper.java` — `@Mapper` (no `componentModel` needed, set globally) with `@Mapping` for `owner.id → ownerId`, `owner.username → ownerUsername`
- [x] 4.4 Add `findByUserWithRoomAndOwner` method to `RoomMemberRepository` — `@Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.room r JOIN FETCH r.owner WHERE rm.user = :user")` returning `List<RoomMember>`
- [x] 4.5 Update `DirectChatService.listDirectChats()` — change return type to `List<DirectChatDto>`, use `findByUserWithRoomAndOwner`, filter for DIRECT visibility, build `DirectChatDto` with the other user's info. Do NOT add `@Transactional` (session-absent mapping)
- [x] 4.6 Update `DirectChatService.getOrCreateDirectChat()` — inject `RoomMapper`, change return type to `RoomDto`, map via `roomMapper.toDto(room)` inside the existing `@Transactional` (write method keeps `@Transactional`)
- [x] 4.7 Update `DirectChatApiController` — change `ResponseEntity<Room>` to `ResponseEntity<RoomDto>` for `createOrGetDirectChat`, change `ResponseEntity<List<Room>>` to `ResponseEntity<List<DirectChatDto>>` for `listDirectChats`
- [x] 4.8 Verify compilation with `./mvnw compile`

## Task 5: UserBanService DTO conversion

- [x] 5.1 Create `UserBanDto` record in `src/main/java/com/bovae/yac/model/dto/UserBanDto.java` with fields: `UUID id`, `UUID blockedId`, `String blockedUsername`, `String blockedDisplayName`, `Instant createdAt`
- [x] 5.2 Create `UserBanMapper` interface in `src/main/java/com/bovae/yac/model/dto/UserBanMapper.java` — `@Mapper` (no `componentModel` needed, set globally) with `@Mapping` for `blocked.id → blockedId`, `blocked.username → blockedUsername`, `blocked.displayName → blockedDisplayName`
- [x] 5.3 Add `findByBlockerWithBlocked` method to `UserBanRepository` — `@Query("SELECT ub FROM UserBan ub JOIN FETCH ub.blocked WHERE ub.blocker = :blocker")` returning `List<UserBan>`
- [x] 5.4 Update `UserBanService.listBannedUsers()` — inject `UserBanMapper`, change return type to `List<UserBanDto>`, use `findByBlockerWithBlocked`, map via `userBanMapper.toDtoList(...)`. Do NOT add `@Transactional` (session-absent mapping)
- [x] 5.5 Verify compilation with `./mvnw compile`

## Task 6: RoomService DTO conversion

- [x] 6.1 Add `findByIdWithOwner` method to `RoomRepository` — `@Query("SELECT r FROM Room r JOIN FETCH r.owner WHERE r.id = :id")` returning `Optional<Room>`
- [x] 6.2 Inject `RoomMapper` into `RoomService`, update `createRoom()` return type to `RoomDto`, map via `roomMapper.toDto(room)` inside the existing `@Transactional` (write method keeps `@Transactional`)
- [x] 6.3 Add `getRoomDtoById(UUID)` method to `RoomService` — uses `findByIdWithOwner`, returns `RoomDto` via `roomMapper.toDto(room)`. Keep existing `getRoomById(UUID)` returning `Room` entity for internal use
- [x] 6.4 Verify compilation with `./mvnw compile`

## Task 7: UserService DTO conversion

- [x] 7.1 Create `UserDto` record in `src/main/java/com/bovae/yac/model/dto/UserDto.java` with fields: `UUID id`, `String email`, `String username`, `String displayName`, `Instant createdAt` — excludes `passwordHash`
- [x] 7.2 Create `UserMapper` interface in `src/main/java/com/bovae/yac/model/dto/UserMapper.java` — `@Mapper` (no `componentModel` needed, set globally), no `@Mapping` annotations needed (field names match, `passwordHash` is ignored since no corresponding DTO field)
- [x] 7.3 Inject `UserMapper` into `UserService`, update `register()` return type to `UserDto`, update `updateProfile()` return type to `UserDto`, update `getById()` return type to `UserDto`, update `findByUsername()` return type to `Optional<UserDto>` — map via `userMapper.toDto(...)`. No `JOIN FETCH` needed (User has no lazy associations)
- [x] 7.4 Update controllers that consume `UserService.getById()`, `register()`, `updateProfile()`, `findByUsername()` to use `UserDto` instead of `User` entity. Note: controllers that need the `User` entity for auth/resolution (e.g., `resolveUser` via `userRepository.findByEmail`) continue using the repository directly
- [x] 7.5 Verify compilation with `./mvnw compile`

## Task 8: Integration tests — session-absent mapping verification

- [x] 8.1 Write integration test for `RoomMemberService.listMembers()` — persist a room with members, call `listMembers` (no `@Transactional` on test), verify all `RoomMemberDto` fields are populated, verify DTO count matches member count
- [x] 8.2 Write integration test for `FriendshipService.listFriends()` — persist users with ACCEPTED friendships, call `listFriends` (no `@Transactional` on test), verify all `FriendshipDto` fields are populated
- [x] 8.3 Write integration test for `DirectChatService.listDirectChats()` — persist a direct chat room, call `listDirectChats` (no `@Transactional` on test), verify all `DirectChatDto` fields are populated
- [x] 8.4 Write integration test for `UserBanService.listBannedUsers()` — persist a user ban, call `listBannedUsers` (no `@Transactional` on test), verify all `UserBanDto` fields are populated

## Task 9: Unit tests — MapStruct mapper correctness

- [x] 9.1 Write unit tests for `RoomMemberMapper` — verify all field mappings, verify null `displayName` maps correctly
- [x] 9.2 Write unit tests for `FriendshipMapper` — verify all requester/recipient field mappings
- [x] 9.3 Write unit tests for `RoomMapper` — verify owner field mappings
- [x] 9.4 Write unit tests for `UserMapper` — verify all fields map correctly, verify `passwordHash` is NOT present in `UserDto`
- [x] 9.5 Write unit tests for `UserBanMapper` — verify blocked user field mappings

## Task 10: Property-based tests (jqwik) — preservation and fix verification

- [x] 10.1 [PBT: Property 1] Write property-based test for Bug Condition — generate random room configurations (1–10 members with varied roles and nullable displayNames), persist them, call `listMembers()` outside a transaction, assert all `RoomMemberDto` records have non-null `userId`, `username`, `role`, `joinedAt`, and that DTO count matches member count
- [x] 10.2 [PBT: Property 2] Write property-based test for Preservation — generate random room/user pairs, verify `isMember()`, `existsByRoomAndUser()` produce identical results before and after the DTO changes, verify `joinPublicRoom()` and `leaveRoom()` continue to work correctly
- [x] 10.3 [PBT: Property 3] Write property-based test for Preservation — generate random user pairs, verify `areFriends()` and `isBanExistsBetween()` produce identical boolean results
