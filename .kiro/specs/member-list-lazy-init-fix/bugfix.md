# Bugfix Requirements Document

## Introduction

Opening a room page causes a 500 Internal Server Error due to a `LazyInitializationException`. The `RoomMember.user` association is mapped with `FetchType.LAZY`, and `RoomMemberRepository.findByRoom()` does not eagerly fetch the `user` association. Since `open-in-view` is `false`, the Hibernate session is closed before Thymeleaf renders the `member-list` fragment, which accesses `member.user.displayName`, `member.user.username`, and `member.user.id` — all of which require initializing the lazy `User` proxy.

This bug is a symptom of a systemic architectural issue: services return raw JPA entities to controllers and templates, allowing lazy proxies to leak past the persistence boundary. The fix adopts a DTO-first architecture across all services — every service method that returns data to controllers or templates returns DTO records instead of JPA entities. MapStruct mappers convert entities to DTOs at the service layer, and mapping deliberately happens after the Hibernate session closes (no `@Transactional` on read-only service methods). This makes the fix self-verifying: if a `JOIN FETCH` is missing, MapStruct fails immediately at the service layer with `LazyInitializationException`, rather than silently passing and failing at the template/controller layer.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a user navigates to a room page that has one or more members THEN the system throws `org.hibernate.LazyInitializationException: Could not initialize proxy [com.bovae.yac.model.entity.User#...]` and returns a 500 error, because `RoomMemberService.listMembers()` calls `RoomMemberRepository.findByRoom()` which does not fetch-join the `user` association, and the Hibernate session is already closed when Thymeleaf accesses `member.user` properties

1.2 WHEN `RoomMemberService.listMembers()` is called THEN the returned `RoomMember` entities have uninitialized lazy `user` proxies, because the method has no `@Transactional` annotation and the repository query has no fetch join or `@EntityGraph`

1.3 WHEN any service returns JPA entities with lazy associations to a controller or template THEN those entities carry uninitialized proxy references that will throw `LazyInitializationException` if accessed outside the persistence context, because `open-in-view` is `false` and no view-scoped session exists

### Expected Behavior (Correct)

2.1 WHEN a user navigates to a room page that has one or more members THEN the system SHALL render the member list successfully, displaying each member's display name (or username as fallback), role badge, and presence dot without any `LazyInitializationException`

2.2 WHEN any service method returns data to a controller or template THEN it SHALL return DTO records (not JPA entities), with all required fields fully populated, so that accessing DTO fields outside the persistence context is always safe. This applies to all services: `RoomMemberService`, `RoomService`, `MessageService`, `UserService`, `FriendshipService`, `UserBanService`, `DirectChatService`, and `ModerationService`

2.3 WHEN a read-only service method (e.g., `listMembers`, `listFriends`, `listDirectChats`, `listBannedUsers`) maps entities to DTOs THEN the mapping SHALL occur after the Hibernate session has closed (no `@Transactional` on the service method), so that a missing `JOIN FETCH` causes an immediate `LazyInitializationException` at the mapping step rather than silently passing

2.4 WHEN a service method needs a lazy association for DTO mapping THEN it SHALL use a dedicated repository method with `JOIN FETCH` (e.g., `findByRoomWithUsers`) rather than modifying the existing lean query, so that callers who do not need the association are not forced to load it

### Constraints

- The `RoomMember.user` association MUST remain `FetchType.LAZY` on the entity mapping — changing to `EAGER` is not acceptable as it would cause N+1 problems across all other code paths that load `RoomMember` without needing the `user` association
- All other `FetchType.LAZY` associations (`Friendship.requester`, `Friendship.recipient`, `Room.owner`, `UserBan.blocker`, `UserBan.blocked`, `Message.sender`, `Message.room`, `Message.replyTo`) MUST remain `LAZY`
- The fix MUST use targeted `JOIN FETCH` queries in separate repository methods — existing derived queries MUST NOT be modified
- Read-only service methods MUST NOT add `@Transactional` solely to keep the session open for mapping — the session-absent mapping pattern is a deliberate design choice for self-verification
- MapStruct mappers MUST use `componentModel = "spring"` set globally via `maven-compiler-plugin` compiler arg (`-Amapstruct.defaultComponentModel=spring`) — individual `@Mapper` interfaces do not specify `componentModel`
- DTO records MUST NOT expose sensitive fields (e.g., `User.passwordHash` MUST NOT appear in any DTO)

### Root Cause of Missing Test Coverage

No existing test exercises `listMembers()` in a detached-entity scenario (outside a transaction boundary). All existing tests either run within an open Hibernate session or don't access lazy associations after the service call returns. This gap allowed the `LazyInitializationException` to reach production undetected. The same gap exists for `FriendshipService.listFriends()`, `DirectChatService.listDirectChats()`, `UserBanService.listBannedUsers()`, and `RoomService.getRoomById()` — all of which return entities with lazy associations without `@Transactional`.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a user navigates to a room page with no members THEN the system SHALL CONTINUE TO render the member list with a "No members" placeholder

3.2 WHEN `RoomMemberService.joinPublicRoom()` is called THEN the system SHALL CONTINUE TO create a new `RoomMember` and return successfully

3.3 WHEN `RoomMemberService.leaveRoom()` is called THEN the system SHALL CONTINUE TO remove the member from the room

3.4 WHEN `RoomMemberService.isMember()` is called THEN the system SHALL CONTINUE TO return the correct boolean membership status

3.5 WHEN `RoomMemberRepository.findByUser()` is called THEN the system SHALL CONTINUE TO return room members for a given user without side effects from the fix

3.6 WHEN `FriendshipService.areFriends()` is called THEN the system SHALL CONTINUE TO return the correct boolean friendship status

3.7 WHEN `RoomService.deleteRoom()` and `RoomService.deleteRoomCascade()` are called THEN the system SHALL CONTINUE TO cascade-delete all related entities correctly

3.8 WHEN `ModerationService` methods (kick, ban, unban, grantAdmin, revokeAdmin) are called THEN the system SHALL CONTINUE TO perform moderation actions correctly

3.9 WHEN `MessageService.sendMessage()` and `MessageService.editMessage()` are called THEN the system SHALL CONTINUE TO persist messages and return successfully

3.10 WHEN existing repository derived queries (`findByRoom`, `findByUser`, `findByBlocker`, etc.) are called by internal service logic THEN they SHALL CONTINUE TO work unchanged — new `JOIN FETCH` methods are additive, not replacements
