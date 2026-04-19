package com.bovae.yac.unit;

import com.bovae.yac.exception.ForbiddenException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DirectChatService}.
 *
 * <p>Validates Correctness Property: CP 20.
 * <p>Requirements: 4.10, 4.11, 14.4, 14.7.
 */
@ExtendWith(MockitoExtension.class)
class DirectChatServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private UserBanService userBanService;

    @Mock
    private RoomMapper roomMapper;

    @InjectMocks
    private DirectChatService directChatService;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        userA = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("$2a$10$hash")
                .build();

        userB = User.builder()
                .id(UUID.randomUUID())
                .email("bob@test.com")
                .username("bob")
                .passwordHash("$2a$10$hash")
                .build();
    }

    /**
     * Validates CP 20: getOrCreateDirectChat succeeds when users are friends
     * and no mutual UserBan exists, creating a new DIRECT room with both users as members.
     */
    @Test
    void getOrCreateDirectChat_whenFriendsAndNoBan_createsDirectChat() {
        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(false);
        when(roomMemberRepository.findByUser(userA)).thenReturn(Collections.emptyList());
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            return new RoomDto(r.getId(), r.getName(), r.getDescription(), r.getVisibility(),
                    r.getOwner().getId(), r.getOwner().getUsername(), r.getNextWatermark(), r.getCreatedAt());
        });

        RoomDto result = directChatService.getOrCreateDirectChat(userA, userB);

        assertThat(result).isNotNull();
        assertThat(result.visibility()).isEqualTo(RoomVisibility.DIRECT);
        assertThat(result.ownerId()).isEqualTo(userA.getId());

        verify(roomRepository).save(any(Room.class));
        verify(roomMemberRepository, times(2)).save(any(RoomMember.class));
    }

    /**
     * Validates CP 20: getOrCreateDirectChat fails when users are not friends.
     */
    @Test
    void getOrCreateDirectChat_whenNotFriends_throwsForbiddenException() {
        when(friendshipService.areFriends(userA, userB)).thenReturn(false);

        assertThatThrownBy(() -> directChatService.getOrCreateDirectChat(userA, userB))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("must be friends");

        verify(roomRepository, never()).save(any());
    }

    /**
     * Validates CP 20: getOrCreateDirectChat fails when a mutual UserBan exists.
     */
    @Test
    void getOrCreateDirectChat_whenBanExists_throwsForbiddenException() {
        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(true);

        assertThatThrownBy(() -> directChatService.getOrCreateDirectChat(userA, userB))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ban exists");

        verify(roomRepository, never()).save(any());
    }

    /**
     * Validates CP 20: getOrCreateDirectChat returns the existing direct chat
     * when one already exists between the two users.
     */
    @Test
    void getOrCreateDirectChat_whenExistingDirectChat_returnsExistingRoom() {
        Room existingRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("dm-existing")
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .build();

        RoomMember memberA = RoomMember.builder()
                .room(existingRoom)
                .user(userA)
                .role(RoomRole.MEMBER)
                .build();

        RoomMember memberB = RoomMember.builder()
                .room(existingRoom)
                .user(userB)
                .role(RoomRole.MEMBER)
                .build();

        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(false);
        when(roomMemberRepository.findByUser(userA)).thenReturn(List.of(memberA));
        when(roomMemberRepository.findByUser(userB)).thenReturn(List.of(memberB));
        when(roomMapper.toDto(existingRoom)).thenReturn(new RoomDto(
                existingRoom.getId(), existingRoom.getName(), existingRoom.getDescription(),
                existingRoom.getVisibility(), userA.getId(), userA.getUsername(), null, null));

        RoomDto result = directChatService.getOrCreateDirectChat(userA, userB);

        assertThat(result.id()).isEqualTo(existingRoom.getId());
        verify(roomRepository, never()).save(any());
    }

    /**
     * Validates Requirement 6.1: getOrCreateDirectChat with self creates a self-DM (Saved Messages).
     * The self-check was removed to allow self-DM rooms.
     */
    @Test
    void getOrCreateDirectChat_withSelf_createsSelfDm() {
        when(roomMemberRepository.findByUser(userA)).thenReturn(Collections.emptyList());
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            return new RoomDto(r.getId(), r.getName(), r.getDescription(), r.getVisibility(),
                    r.getOwner().getId(), r.getOwner().getUsername(), r.getNextWatermark(), r.getCreatedAt());
        });

        RoomDto result = directChatService.getOrCreateDirectChat(userA, userA);

        assertThat(result).isNotNull();
        assertThat(result.visibility()).isEqualTo(RoomVisibility.DIRECT);
        verify(roomRepository).save(any(Room.class));
        verify(roomMemberRepository).save(any(RoomMember.class));
        verify(friendshipService, never()).areFriends(any(), any());
    }

    /**
     * Validates CP 20 (idempotency): calling getOrCreateDirectChat twice for the same
     * pair returns the same existing room on the second call without creating a duplicate.
     */
    @Test
    void getOrCreateDirectChat_calledTwice_returnsExistingRoomIdempotently() {
        // First call: no existing chat, creates one
        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(false);

        Room createdRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("dm-test")
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .build();

        // First call setup: no existing direct chat
        when(roomMemberRepository.findByUser(userA)).thenReturn(Collections.emptyList());
        when(roomRepository.save(any(Room.class))).thenReturn(createdRoom);
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            return new RoomDto(r.getId(), r.getName(), r.getDescription(), r.getVisibility(),
                    r.getOwner().getId(), r.getOwner().getUsername(), r.getNextWatermark(), r.getCreatedAt());
        });

        RoomDto firstResult = directChatService.getOrCreateDirectChat(userA, userB);
        assertThat(firstResult.id()).isEqualTo(createdRoom.getId());

        // Second call setup: existing direct chat found
        RoomMember memberA = RoomMember.builder()
                .room(createdRoom)
                .user(userA)
                .role(RoomRole.MEMBER)
                .build();

        RoomMember memberB = RoomMember.builder()
                .room(createdRoom)
                .user(userB)
                .role(RoomRole.MEMBER)
                .build();

        when(roomMemberRepository.findByUser(userA)).thenReturn(List.of(memberA));
        when(roomMemberRepository.findByUser(userB)).thenReturn(List.of(memberB));

        RoomDto secondResult = directChatService.getOrCreateDirectChat(userA, userB);

        assertThat(secondResult.id()).isEqualTo(createdRoom.getId());
    }
}
