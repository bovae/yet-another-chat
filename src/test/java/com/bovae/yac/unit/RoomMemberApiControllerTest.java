package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.RoomMemberApiController;
import com.bovae.yac.controller.api.RoomMemberApiController.ChangeRoleRequest;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link RoomMemberApiController}. */
@ExtendWith(MockitoExtension.class)
class RoomMemberApiControllerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private RoomMemberApiController controller;

    private User caller;
    private Room room;
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
        room = Room.builder()
                .id(roomId)
                .name("room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(caller)
                .build();

        lenient().when(principal.getName()).thenReturn(caller.getEmail());
        lenient().when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
        lenient().when(roomService.getRoomById(roomId)).thenReturn(room);
    }

    // --- listMembers ---

    @Test
    void listMembers_shouldReturnMembers_whenCallerCanRead() {
        RoomMemberDto dto = new RoomMemberDto(caller.getId(), "caller", "Caller", RoomRole.OWNER, Instant.now());
        when(roomMemberService.listMembers(room)).thenReturn(List.of(dto));

        ResponseEntity<List<RoomMemberDto>> response = controller.listMembers(roomId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(dto);
        verify(roomMemberService).requireCanRead(room, caller);
    }

    @Test
    void listMembers_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listMembers(roomId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(caller.getEmail());

        verify(roomMemberService, never()).listMembers(room);
    }

    // --- kickMember ---

    @Test
    void kickMember_shouldReturnNoContentAndBroadcast_whenTargetResolves() {
        UUID targetId = UUID.randomUUID();
        User target = User.builder().id(targetId).username("victim").build();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        ResponseEntity<Void> response = controller.kickMember(roomId, targetId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(moderationService).kickMember(room, caller, target);
        verify(messageBroadcastService).broadcastMembership(room, target, "MEMBER_BANNED");
    }

    @Test
    void kickMember_shouldThrowNotFound_whenTargetUserMissing() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.kickMember(roomId, targetId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(targetId.toString());

        verify(moderationService, never()).kickMember(room, caller, target(targetId));
    }

    // --- changeRole ---

    @Test
    void changeRole_shouldGrantAdmin_whenRoleIsAdmin() {
        UUID targetId = UUID.randomUUID();
        User target = target(targetId);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        ResponseEntity<Void> response =
                controller.changeRole(roomId, targetId, new ChangeRoleRequest(RoomRole.ADMIN), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(moderationService).grantAdminRole(room, caller, target);
        verify(moderationService, never()).revokeAdminRole(room, caller, target);
    }

    @Test
    void changeRole_shouldRevokeAdmin_whenRoleIsMember() {
        UUID targetId = UUID.randomUUID();
        User target = target(targetId);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        ResponseEntity<Void> response =
                controller.changeRole(roomId, targetId, new ChangeRoleRequest(RoomRole.MEMBER), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(moderationService).revokeAdminRole(room, caller, target);
        verify(moderationService, never()).grantAdminRole(room, caller, target);
    }

    private User target(UUID id) {
        return User.builder().id(id).username("victim").build();
    }
}
