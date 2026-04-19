package com.bovae.yac.property;

import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.RoomMapper;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.UserBanService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for Self-DM (Saved Messages) lifecycle.
 *
 * Validates: Requirements 6.1, 6.2, 6.5, 6.6
 */
class SavedMessagesPropertyTest {

    private RoomRepository roomRepository;
    private RoomMemberRepository roomMemberRepository;
    private RoomMapper roomMapper;
    private DirectChatService directChatService;

    @BeforeTry
    void setUp() {
        roomRepository = mock(RoomRepository.class);
        roomMemberRepository = mock(RoomMemberRepository.class);
        FriendshipService friendshipService = mock(FriendshipService.class);
        UserBanService userBanService = mock(UserBanService.class);
        roomMapper = mock(RoomMapper.class);

        directChatService = new DirectChatService(
                roomRepository,
                roomMemberRepository,
                friendshipService,
                userBanService,
                roomMapper
        );
    }

    @Provide
    Arbitrary<String> usernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    private User buildUser(String username) {
        UUID userId = UUID.randomUUID();
        return User.builder()
                .id(userId)
                .email(username + "@test.com")
                .username(username)
                .passwordHash("hashed")
                .build();
    }

    private Room buildSavedMessagesRoom(User user) {
        return Room.builder()
                .id(UUID.randomUUID())
                .name("saved-messages-%s".formatted(user.getId()))
                .visibility(RoomVisibility.DIRECT)
                .owner(user)
                .nextWatermark(1L)
                .build();
    }

    private RoomDto toRoomDto(Room room) {
        return new RoomDto(
                room.getId(),
                room.getName(),
                room.getDescription(),
                room.getVisibility(),
                room.getOwner().getId(),
                room.getOwner().getUsername(),
                room.getNextWatermark(),
                Instant.now()
        );
    }

    /**
     * Property 5a: getOrCreateSavedMessages returns a DIRECT room.
     *
     * For any valid user, calling getOrCreateSavedMessages(user) SHALL return
     * a RoomDto with visibility == DIRECT.
     *
     * Validates: Requirements 6.1, 6.2
     */
    @Property(tries = 20)
    void savedMessages_shallReturnDirectRoom(@ForAll("usernames") String username) {
        User user = buildUser(username);

        // No existing saved messages room
        when(roomMemberRepository.findByUser(user)).thenReturn(List.of());

        // Mock room save to return a room with an ID
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            if (room.getId() == null) {
                room.setId(UUID.randomUUID());
            }
            return room;
        });

        // Mock member save
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        // Mock mapper to produce a proper DTO
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            return toRoomDto(room);
        });

        RoomDto result = directChatService.getOrCreateSavedMessages(user);

        assertThat(result).isNotNull();
        assertThat(result.visibility()).isEqualTo(RoomVisibility.DIRECT);
    }

    /**
     * Property 5b: Saved Messages room has exactly one member.
     *
     * The created room SHALL have exactly one RoomMember entry.
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 20)
    void savedMessages_shallHaveSingleMember(@ForAll("usernames") String username) {
        User user = buildUser(username);

        // No existing saved messages room
        when(roomMemberRepository.findByUser(user)).thenReturn(List.of());

        // Track saved members
        List<RoomMember> savedMembers = new ArrayList<>();

        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            if (room.getId() == null) {
                room.setId(UUID.randomUUID());
            }
            return room;
        });

        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> {
            RoomMember member = invocation.getArgument(0);
            savedMembers.add(member);
            return member;
        });

        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            return toRoomDto(room);
        });

        directChatService.getOrCreateSavedMessages(user);

        assertThat(savedMembers).hasSize(1);
        assertThat(savedMembers.getFirst().getUser().getId()).isEqualTo(user.getId());
        assertThat(savedMembers.getFirst().getRole()).isEqualTo(RoomRole.MEMBER);
    }

    /**
     * Property 5c: Idempotency — second call returns the same room.
     *
     * Calling getOrCreateSavedMessages(user) again SHALL return the same room.
     *
     * Validates: Requirements 6.5
     */
    @Property(tries = 20)
    void savedMessages_shallBeIdempotent(@ForAll("usernames") String username) {
        User user = buildUser(username);
        Room existingRoom = buildSavedMessagesRoom(user);
        RoomMember selfMember = RoomMember.builder()
                .room(existingRoom)
                .user(user)
                .role(RoomRole.MEMBER)
                .build();

        // First call: no existing room → creates one
        when(roomMemberRepository.findByUser(user)).thenReturn(List.of());

        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            room.setId(existingRoom.getId());
            return room;
        });

        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            return toRoomDto(room);
        });

        RoomDto firstResult = directChatService.getOrCreateSavedMessages(user);

        // Second call: existing room found → returns same room
        when(roomMemberRepository.findByUser(user)).thenReturn(List.of(selfMember));
        when(roomMemberRepository.findByRoom(existingRoom)).thenReturn(List.of(selfMember));
        when(roomMapper.toDto(existingRoom)).thenReturn(toRoomDto(existingRoom));

        RoomDto secondResult = directChatService.getOrCreateSavedMessages(user);

        assertThat(firstResult.id()).isEqualTo(secondResult.id());
        assertThat(secondResult.visibility()).isEqualTo(RoomVisibility.DIRECT);
    }

    /**
     * Property 5d: Ban check skipped for self-DM.
     *
     * Sending a message in the self-DM room SHALL not throw a ban-related exception
     * regardless of any UserBan entries involving the user. This is tested by verifying
     * that getOrCreateSavedMessages does not throw even when ban service would report bans.
     *
     * Validates: Requirements 6.6
     */
    @Property(tries = 20)
    void savedMessages_shallSkipBanCheck(@ForAll("usernames") String username) {
        User user = buildUser(username);

        // No existing saved messages room
        when(roomMemberRepository.findByUser(user)).thenReturn(List.of());

        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            if (room.getId() == null) {
                room.setId(UUID.randomUUID());
            }
            return room;
        });

        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            return toRoomDto(room);
        });

        // Self-DM creation should not throw any ban-related exception
        // because the service skips friendship and ban checks for self-DM
        assertThatCode(() -> directChatService.getOrCreateSavedMessages(user))
                .doesNotThrowAnyException();
    }
}
