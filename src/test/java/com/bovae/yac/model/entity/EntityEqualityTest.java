package com.bovae.yac.model.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract tests for entity {@code equals}/{@code hashCode}. All JPA entities share the same
 * id-based (or composite-key based) equality shape, so the branches are exercised generically
 * across every entity. The composite-key cases drive all 16 branches of
 * {@link CompositeKeys#roomUserEquals} through {@link RoomMember} and {@link UnreadMarker}.
 */
class EntityEqualityTest {

    // --- shared contract: holds for every entity ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("transientEntities")
    void equals_shouldReturnTrue_whenSameTransientInstance(String name, Supplier<Object> factory) {
        Object entity = factory.get();
        Object same = entity;
        // Null ids make id-based equality false, so reflexivity holds only via the this == o short-circuit.
        assertEquals(entity, same, name + " must equal itself even before persistence (null id)");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    void equals_shouldReturnFalse_whenComparedToDifferentType(String name, Supplier<Object> factory) {
        Object entity = factory.get();
        assertNotEquals(entity, "not-a-" + name, name + " must not equal an unrelated type");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    void equals_shouldReturnFalse_whenArgumentIsNull(String name, Supplier<Object> factory) {
        Object entity = factory.get();
        assertFalse(entity.equals(null), name + " must not equal null");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEntities")
    void hashCode_shouldBeClassConstant_regardlessOfState(String name, Supplier<Object> factory) {
        Object first = factory.get();
        Object second = factory.get();
        assertEquals(first.hashCode(), second.hashCode(), name + " must hash by class, not by id or identity");
    }

    // --- id-based equality: single-id entities ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleIdEntities")
    void equals_shouldReturnTrue_whenIdsMatch(String name, Function<UUID, Object> factory) {
        UUID id = UUID.randomUUID();
        Object left = factory.apply(id);
        Object right = factory.apply(id);
        assertEquals(left, right, name + " with the same id must be equal");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleIdEntities")
    void equals_shouldReturnFalse_whenIdsDiffer(String name, Function<UUID, Object> factory) {
        Object left = factory.apply(UUID.randomUUID());
        Object right = factory.apply(UUID.randomUUID());
        assertNotEquals(left, right, name + " with different ids must not be equal");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleIdEntities")
    void equals_shouldReturnFalse_whenIdIsNull(String name, Function<UUID, Object> factory) {
        Object transientEntity = factory.apply(null);
        Object persistedEntity = factory.apply(UUID.randomUUID());
        assertNotEquals(transientEntity, persistedEntity, name + " with a null id must not equal anything");
    }

    // --- composite-key equality: exercises all 16 branches of CompositeKeys.roomUserEquals ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("compositeEqualsCases")
    void equals_shouldFollowCompositeKeyContract(String description, Object left, Object right, boolean expected) {
        assertEquals(expected, left.equals(right), description);
    }

    // --- providers ---

    private static Stream<Arguments> allEntities() {
        return Stream.of(
                entity(
                        "Attachment",
                        () -> Attachment.builder().id(UUID.randomUUID()).build()),
                entity(
                        "Friendship",
                        () -> Friendship.builder().id(UUID.randomUUID()).build()),
                entity("Message", () -> Message.builder().id(UUID.randomUUID()).build()),
                entity(
                        "PasswordResetToken",
                        () -> PasswordResetToken.builder().id(UUID.randomUUID()).build()),
                entity("Room", () -> Room.builder().id(UUID.randomUUID()).build()),
                entity("RoomBan", () -> RoomBan.builder().id(UUID.randomUUID()).build()),
                entity(
                        "RoomInvitation",
                        () -> RoomInvitation.builder().id(UUID.randomUUID()).build()),
                entity("User", () -> User.builder().id(UUID.randomUUID()).build()),
                entity("UserBan", () -> UserBan.builder().id(UUID.randomUUID()).build()),
                entity("RoomMember", () -> roomMember(room(UUID.randomUUID()), user(UUID.randomUUID()))),
                entity("UnreadMarker", () -> unreadMarker(room(UUID.randomUUID()), user(UUID.randomUUID()))));
    }

    private static Stream<Arguments> transientEntities() {
        return Stream.of(
                entity("Attachment", () -> Attachment.builder().build()),
                entity("Friendship", () -> Friendship.builder().build()),
                entity("Message", () -> Message.builder().build()),
                entity("PasswordResetToken", () -> PasswordResetToken.builder().build()),
                entity("Room", () -> Room.builder().build()),
                entity("RoomBan", () -> RoomBan.builder().build()),
                entity("RoomInvitation", () -> RoomInvitation.builder().build()),
                entity("User", () -> User.builder().build()),
                entity("UserBan", () -> UserBan.builder().build()),
                entity("RoomMember", () -> roomMember(null, null)),
                entity("UnreadMarker", () -> unreadMarker(null, null)));
    }

    private static Stream<Arguments> singleIdEntities() {
        return Stream.of(
                Arguments.of("Attachment", (Function<UUID, Object>)
                        id -> Attachment.builder().id(id).build()),
                Arguments.of("Friendship", (Function<UUID, Object>)
                        id -> Friendship.builder().id(id).build()),
                Arguments.of("Message", (Function<UUID, Object>)
                        id -> Message.builder().id(id).build()),
                Arguments.of("PasswordResetToken", (Function<UUID, Object>)
                        id -> PasswordResetToken.builder().id(id).build()),
                Arguments.of("Room", (Function<UUID, Object>)
                        id -> Room.builder().id(id).build()),
                Arguments.of("RoomBan", (Function<UUID, Object>)
                        id -> RoomBan.builder().id(id).build()),
                Arguments.of("RoomInvitation", (Function<UUID, Object>)
                        id -> RoomInvitation.builder().id(id).build()),
                Arguments.of("User", (Function<UUID, Object>)
                        id -> User.builder().id(id).build()),
                Arguments.of("UserBan", (Function<UUID, Object>)
                        id -> UserBan.builder().id(id).build()));
    }

    private static Stream<Arguments> compositeEqualsCases() {
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        // desc, leftRoom, leftUser, rightRoom, rightUser, expected
        List<Object[]> scenarios = List.of(
                new Object[] {"matching room and user", room(roomId), user(userId), room(roomId), user(userId), true},
                new Object[] {"null room on left", null, user(userId), room(roomId), user(userId), false},
                new Object[] {"null user on left", room(roomId), null, room(roomId), user(userId), false},
                new Object[] {"null room on right", room(roomId), user(userId), null, user(userId), false},
                new Object[] {"null user on right", room(roomId), user(userId), room(roomId), null, false},
                new Object[] {"different room id", room(roomId), user(userId), room(otherRoomId), user(userId), false},
                new Object[] {"different user id", room(roomId), user(userId), room(roomId), user(otherUserId), false});

        List<Arguments> arguments = new ArrayList<>();
        for (Object[] s : scenarios) {
            Room leftRoom = (Room) s[1];
            User leftUser = (User) s[2];
            Room rightRoom = (Room) s[3];
            User rightUser = (User) s[4];
            boolean expected = (boolean) s[5];
            arguments.add(Arguments.of(
                    "RoomMember: " + s[0], roomMember(leftRoom, leftUser), roomMember(rightRoom, rightUser), expected));
            arguments.add(Arguments.of(
                    "UnreadMarker: " + s[0],
                    unreadMarker(leftRoom, leftUser),
                    unreadMarker(rightRoom, rightUser),
                    expected));
        }
        return arguments.stream();
    }

    // --- helpers ---

    private static Arguments entity(String name, Supplier<Object> factory) {
        return Arguments.of(name, factory);
    }

    private static Room room(UUID id) {
        return Room.builder().id(id).build();
    }

    private static User user(UUID id) {
        return User.builder().id(id).build();
    }

    private static RoomMember roomMember(Room room, User user) {
        return RoomMember.builder().room(room).user(user).build();
    }

    private static UnreadMarker unreadMarker(Room room, User user) {
        return UnreadMarker.builder().room(room).user(user).build();
    }
}
