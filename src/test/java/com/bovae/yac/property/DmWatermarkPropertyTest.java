package com.bovae.yac.property;

import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.RoomMapper;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
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
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for DM room nextWatermark initialization.
 *
 * Validates: Requirements 16.1, 16.2
 */
class DmWatermarkPropertyTest {

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

        // Default: users are friends and no bans
        when(friendshipService.areFriends(any(User.class), any(User.class))).thenReturn(true);
        when(userBanService.isBanExistsBetween(any(User.class), any(User.class))).thenReturn(false);
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
        return User.builder()
                .id(UUID.randomUUID())
                .email(username + "@test.com")
                .username(username)
                .passwordHash("hashed")
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
     * Property 10a: Normal DM (two different users) → nextWatermark == 1L
     *
     * For any direct chat room created via DirectChatService.getOrCreateDirectChat()
     * with two different users, the persisted Room entity SHALL have nextWatermark == 1L.
     *
     * Validates: Requirements 16.1, 16.2
     */
    @Property(tries = 20)
    void normalDm_shallHaveNextWatermarkOne(
            @ForAll("usernames") String usernameA,
            @ForAll("usernames") String usernameB
    ) {
        User userA = buildUser(usernameA + "a");
        User userB = buildUser(usernameB + "b");

        // No existing DM between these users
        when(roomMemberRepository.findByUser(userA)).thenReturn(List.of());

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

        directChatService.getOrCreateDirectChat(userA, userB);

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).save(roomCaptor.capture());

        Room savedRoom = roomCaptor.getValue();
        assertThat(savedRoom.getNextWatermark()).isEqualTo(1L);
        assertThat(savedRoom.getVisibility()).isEqualTo(RoomVisibility.DIRECT);
    }

    /**
     * Property 10b: Self-DM (saved messages) → nextWatermark == 1L
     *
     * For any direct chat room created via DirectChatService.getOrCreateDirectChat()
     * as a self-DM, the persisted Room entity SHALL have nextWatermark == 1L.
     *
     * Validates: Requirements 16.1, 16.2
     */
    @Property(tries = 20)
    void selfDm_shallHaveNextWatermarkOne(@ForAll("usernames") String username) {
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

        directChatService.getOrCreateDirectChat(user, user);

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository).save(roomCaptor.capture());

        Room savedRoom = roomCaptor.getValue();
        assertThat(savedRoom.getNextWatermark()).isEqualTo(1L);
        assertThat(savedRoom.getVisibility()).isEqualTo(RoomVisibility.DIRECT);
    }
}
