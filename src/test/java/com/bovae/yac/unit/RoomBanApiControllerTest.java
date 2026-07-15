package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.RoomBanApiController;
import com.bovae.yac.controller.api.RoomBanApiController.BanRequest;
import com.bovae.yac.controller.api.RoomBanApiController.BanResponse;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link RoomBanApiController}. */
@ExtendWith(MockitoExtension.class)
class RoomBanApiControllerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private RoomBanRepository roomBanRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private RoomBanApiController controller;

    private User caller;
    private Room ownedRoom;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        caller = User.builder()
                .id(UUID.randomUUID())
                .email("caller@test.com")
                .username("caller")
                .displayName("Caller")
                .passwordHash("hashed")
                .build();
        ownedRoom = Room.builder()
                .id(roomId)
                .name("room")
                .visibility(RoomVisibility.PRIVATE)
                .owner(caller)
                .build();

        lenient().when(principal.getName()).thenReturn(caller.getEmail());
        lenient().when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
        lenient().when(roomService.getRoomById(roomId)).thenReturn(ownedRoom);
    }

    // --- listBans ---

    @Test
    void listBans_shouldReturnMappedBans_whenCallerIsRoomOwner() {
        UUID banId = UUID.randomUUID();
        User banned = User.builder().id(UUID.randomUUID()).username("banned").build();
        Instant createdAt = Instant.now();
        RoomBan ban = RoomBan.builder()
                .id(banId)
                .user(banned)
                .bannedBy(caller)
                .createdAt(createdAt)
                .build();
        when(roomBanRepository.findByRoomWithUserAndBannedBy(ownedRoom)).thenReturn(List.of(ban));

        ResponseEntity<List<BanResponse>> response = controller.listBans(roomId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        BanResponse dto = response.getBody().get(0);
        assertThat(dto.id()).isEqualTo(banId);
        assertThat(dto.userId()).isEqualTo(banned.getId());
        assertThat(dto.username()).isEqualTo("banned");
        assertThat(dto.bannedById()).isEqualTo(caller.getId());
        assertThat(dto.bannedByUsername()).isEqualTo("caller");
        assertThat(dto.createdAt()).isEqualTo(createdAt);
    }

    @ParameterizedTest(name = "role={0} may view the ban list")
    @EnumSource(
            value = RoomRole.class,
            names = {"ADMIN", "OWNER"})
    void listBans_shouldReturnBans_whenCallerIsAuthorizedMember(RoomRole role) {
        Room foreignRoom = foreignRoom();
        when(roomService.getRoomById(roomId)).thenReturn(foreignRoom);
        RoomMember member =
                RoomMember.builder().room(foreignRoom).user(caller).role(role).build();
        when(roomMemberRepository.findById(new RoomMemberId(foreignRoom.getId(), caller.getId())))
                .thenReturn(Optional.of(member));
        when(roomBanRepository.findByRoomWithUserAndBannedBy(foreignRoom)).thenReturn(List.of());

        ResponseEntity<List<BanResponse>> response = controller.listBans(roomId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void listBans_shouldThrowForbidden_whenCallerIsPlainMember() {
        Room foreignRoom = foreignRoom();
        when(roomService.getRoomById(roomId)).thenReturn(foreignRoom);
        RoomMember member = RoomMember.builder()
                .room(foreignRoom)
                .user(caller)
                .role(RoomRole.MEMBER)
                .build();
        when(roomMemberRepository.findById(new RoomMemberId(foreignRoom.getId(), caller.getId())))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> controller.listBans(roomId, principal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("owners and admins");

        verify(roomBanRepository, never()).findByRoomWithUserAndBannedBy(foreignRoom);
    }

    @Test
    void listBans_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listBans(roomId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(caller.getEmail());
    }

    // --- banUser ---

    @Test
    void banUser_shouldReturnCreatedAndBroadcast_whenTargetResolves() {
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).username("victim").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        ResponseEntity<Void> response = controller.banUser(roomId, new BanRequest(targetId), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(moderationService).banUserFromRoom(ownedRoom, caller, target);
        verify(messageBroadcastService).broadcastMembership(ownedRoom, target, "MEMBER_BANNED");
    }

    @Test
    void banUser_shouldThrowNotFound_whenTargetUserMissing() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.banUser(roomId, new BanRequest(targetId), principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(targetId.toString());

        verify(moderationService, never()).banUserFromRoom(ownedRoom, caller, null);
    }

    // --- unbanUser ---

    @Test
    void unbanUser_shouldReturnNoContent_whenTargetResolves() {
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).username("victim").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        ResponseEntity<Void> response = controller.unbanUser(roomId, targetId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(moderationService).unbanUserFromRoom(ownedRoom, caller, target);
    }

    private Room foreignRoom() {
        User otherOwner = User.builder().id(UUID.randomUUID()).username("owner").build();
        return Room.builder()
                .id(roomId)
                .name("room")
                .visibility(RoomVisibility.PRIVATE)
                .owner(otherOwner)
                .build();
    }
}
