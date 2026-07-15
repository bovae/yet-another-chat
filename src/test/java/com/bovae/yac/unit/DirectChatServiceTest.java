package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.DirectChatDto;
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
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.UserBanService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DirectChatService} — existing rooms are now resolved structurally by the
 * deterministic room name (R1-31, R1-68), not by member-set intersection.
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
    private NotificationService notificationService;

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

    @Test
    void getOrCreateDirectChat_whenFriendsAndNoBan_createsDirectChat() {
        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(false);
        stubNewRoomCreation();

        RoomDto result = directChatService.getOrCreateDirectChat(userA, userB);

        assertThat(result).isNotNull();
        assertThat(result.visibility()).isEqualTo(RoomVisibility.DIRECT);
        assertThat(result.ownerId()).isEqualTo(userA.getId());
        assertThat(result.name()).isEqualTo(expectedDmName(userA.getId(), userB.getId()));
        verify(roomRepository).save(any(Room.class));
        verify(roomMemberRepository, times(2)).save(any(RoomMember.class));
        verify(notificationService).ensureMarker(eq(userA), any(Room.class));
        verify(notificationService).ensureMarker(eq(userB), any(Room.class));
    }

    @Test
    void getOrCreateDirectChat_whenNotFriends_throwsForbiddenException() {
        when(friendshipService.areFriends(userA, userB)).thenReturn(false);

        assertThatThrownBy(() -> directChatService.getOrCreateDirectChat(userA, userB))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("must be friends");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void getOrCreateDirectChat_whenBanExists_throwsForbiddenException() {
        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(true);

        assertThatThrownBy(() -> directChatService.getOrCreateDirectChat(userA, userB))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ban exists");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void getOrCreateDirectChat_whenExistingDirectChat_returnsExistingRoom() {
        Room existingRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("dm-existing")
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .build();

        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(false);
        when(roomRepository.findByName(anyString())).thenReturn(Optional.of(existingRoom));
        when(roomMapper.toDto(existingRoom)).thenReturn(toDto(existingRoom));

        RoomDto result = directChatService.getOrCreateDirectChat(userA, userB);

        assertThat(result.id()).isEqualTo(existingRoom.getId());
        verify(roomRepository, never()).save(any());
    }

    @Test
    void getOrCreateDirectChat_withSelf_createsSelfDm() {
        stubNewRoomCreation();

        RoomDto result = directChatService.getOrCreateDirectChat(userA, userA);

        assertThat(result).isNotNull();
        assertThat(result.visibility()).isEqualTo(RoomVisibility.DIRECT);
        assertThat(result.name()).isEqualTo("saved-messages-" + userA.getId());
        verify(roomRepository).save(any(Room.class));
        verify(roomMemberRepository).save(any(RoomMember.class));
        verify(friendshipService, never()).areFriends(any(), any());
        verify(notificationService).ensureMarker(eq(userA), any(Room.class));
    }

    @Test
    void getOrCreateDirectChat_calledTwice_returnsExistingRoomIdempotently() {
        when(friendshipService.areFriends(userA, userB)).thenReturn(true);
        when(userBanService.isBanExistsBetween(userA, userB)).thenReturn(false);

        Room createdRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("dm-test")
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .build();

        // First call finds nothing and creates; second call finds the created room by name.
        when(roomRepository.findByName(anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(createdRoom));
        when(roomRepository.save(any(Room.class))).thenReturn(createdRoom);
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> toDto(invocation.getArgument(0)));

        RoomDto firstResult = directChatService.getOrCreateDirectChat(userA, userB);
        assertThat(firstResult.id()).isEqualTo(createdRoom.getId());

        RoomDto secondResult = directChatService.getOrCreateDirectChat(userA, userB);
        assertThat(secondResult.id()).isEqualTo(createdRoom.getId());

        verify(roomRepository, times(1)).save(any(Room.class));
    }

    // --- listDirectChats ---

    /**
     * A DIRECT room resolves its counterpart for display; non-DIRECT memberships are skipped.
     */
    @Test
    void listDirectChats_returnsCounterpartAndSkipsNonDirectRooms() {
        Room dmRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("dm-room")
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .nextWatermark(1L)
                .build();
        Room publicRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("public-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(userA)
                .nextWatermark(1L)
                .build();

        RoomMember dmMembership = RoomMember.builder().room(dmRoom).user(userA).build();
        RoomMember publicMembership =
                RoomMember.builder().room(publicRoom).user(userA).build();
        when(roomMemberRepository.findByUserWithRoomAndOwner(userA))
                .thenReturn(List.of(dmMembership, publicMembership));

        RoomMember dmUserA = RoomMember.builder().room(dmRoom).user(userA).build();
        RoomMember dmUserB = RoomMember.builder().room(dmRoom).user(userB).build();
        when(roomMemberRepository.findByRoomWithUsers(dmRoom)).thenReturn(List.of(dmUserA, dmUserB));

        List<DirectChatDto> result = directChatService.listDirectChats(userA);

        assertThat(result).hasSize(1);
        DirectChatDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo(dmRoom.getId());
        assertThat(dto.otherUserId()).isEqualTo(userB.getId());
        assertThat(dto.otherUsername()).isEqualTo("bob");
    }

    /**
     * A self-DM (Saved Messages) has only the user as member, so its entry carries the user's own info.
     */
    @Test
    void listDirectChats_selfDm_usesCurrentUserInfo() {
        Room selfRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("saved-messages")
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .nextWatermark(1L)
                .build();
        RoomMember membership = RoomMember.builder().room(selfRoom).user(userA).build();
        when(roomMemberRepository.findByUserWithRoomAndOwner(userA)).thenReturn(List.of(membership));
        when(roomMemberRepository.findByRoomWithUsers(selfRoom)).thenReturn(List.of(membership));

        List<DirectChatDto> result = directChatService.listDirectChats(userA);

        assertThat(result).hasSize(1);
        DirectChatDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo(selfRoom.getId());
        assertThat(dto.otherUserId()).isEqualTo(userA.getId());
        assertThat(dto.otherUsername()).isEqualTo("alice");
    }

    /** Stubs the create-new-room path: no existing room, save assigns an id, mapper delegates to {@link #toDto}. */
    private void stubNewRoomCreation() {
        when(roomRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> toDto(invocation.getArgument(0)));
    }

    /** Mirrors the production sort so the assertion pins the exact ordered DM name (lo UUID first, hi second). */
    private String expectedDmName(UUID idA, UUID idB) {
        UUID lo = idA.compareTo(idB) <= 0 ? idA : idB;
        UUID hi = idA.compareTo(idB) <= 0 ? idB : idA;
        return "dm-" + lo + "-" + hi;
    }

    private RoomDto toDto(Room r) {
        return new RoomDto(
                r.getId(),
                r.getName(),
                r.getDescription(),
                r.getVisibility(),
                r.getOwner().getId(),
                r.getOwner().getUsername(),
                r.getNextWatermark(),
                r.getCreatedAt());
    }
}
