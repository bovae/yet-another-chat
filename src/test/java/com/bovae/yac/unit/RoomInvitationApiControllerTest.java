package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.RoomInvitationApiController;
import com.bovae.yac.controller.api.RoomInvitationApiController.InviteRequest;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link RoomInvitationApiController}. */
@ExtendWith(MockitoExtension.class)
class RoomInvitationApiControllerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private RoomInvitationRepository roomInvitationRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private RoomInvitationApiController controller;

    private User caller;
    private Room room;
    private UUID roomId;
    private UUID invitationId;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
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
                .visibility(RoomVisibility.PRIVATE)
                .owner(caller)
                .build();

        lenient().when(principal.getName()).thenReturn(caller.getEmail());
        lenient().when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
        lenient().when(roomService.getRoomById(roomId)).thenReturn(room);
    }

    // --- inviteUser ---

    @Test
    void inviteUser_shouldThrowForbidden_whenInviterIsNotMember() {
        UUID inviteeId = UUID.randomUUID();
        stubInvitee(inviteeId);
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.inviteUser(roomId, new InviteRequest(inviteeId), principal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only room members");

        verify(roomInvitationRepository, never()).save(any());
    }

    @Test
    void inviteUser_shouldThrowForbidden_whenInviterIsPlainMember() {
        UUID inviteeId = UUID.randomUUID();
        stubInvitee(inviteeId);
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.of(memberWithRole(RoomRole.MEMBER)));

        assertThatThrownBy(() -> controller.inviteUser(roomId, new InviteRequest(inviteeId), principal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("owners and admins");

        verify(roomInvitationRepository, never()).save(any());
    }

    @ParameterizedTest(name = "role={0} may invite users")
    @EnumSource(
            value = RoomRole.class,
            names = {"OWNER", "ADMIN"})
    void inviteUser_shouldPersistInvitation_whenInviterIsOwnerOrAdmin(RoomRole role) {
        UUID inviteeId = UUID.randomUUID();
        User invitee = stubInvitee(inviteeId);
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.of(memberWithRole(role)));
        when(roomInvitationRepository.findByRoomAndInvitee(room, invitee)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.inviteUser(roomId, new InviteRequest(inviteeId), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(roomInvitationRepository, never()).delete(any());
        verify(roomInvitationRepository).flush();
        ArgumentCaptor<RoomInvitation> captor = ArgumentCaptor.forClass(RoomInvitation.class);
        verify(roomInvitationRepository).save(captor.capture());
        RoomInvitation saved = captor.getValue();
        assertThat(saved.getRoom()).isSameAs(room);
        assertThat(saved.getInviter()).isSameAs(caller);
        assertThat(saved.getInvitee()).isSameAs(invitee);
    }

    @Test
    void inviteUser_shouldDeleteExistingInvitation_whenInviteeAlreadyInvited() {
        UUID inviteeId = UUID.randomUUID();
        User invitee = stubInvitee(inviteeId);
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.of(memberWithRole(RoomRole.OWNER)));
        RoomInvitation existing = invitation(room, caller, invitee);
        when(roomInvitationRepository.findByRoomAndInvitee(room, invitee)).thenReturn(Optional.of(existing));

        ResponseEntity<Void> response = controller.inviteUser(roomId, new InviteRequest(inviteeId), principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The stale invitation is removed before the fresh one is persisted (no duplicates).
        verify(roomInvitationRepository).delete(existing);
        verify(roomInvitationRepository).save(any());
    }

    @Test
    void inviteUser_shouldThrowNotFound_whenInviteeMissing() {
        UUID inviteeId = UUID.randomUUID();
        when(userRepository.findById(inviteeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.inviteUser(roomId, new InviteRequest(inviteeId), principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(inviteeId.toString());
    }

    // --- acceptInvitation ---

    @Test
    void acceptInvitation_shouldJoinAndBroadcast_whenInvitationValid() {
        User inviter = otherUser("inviter");
        RoomInvitation invitation = invitation(room, inviter, caller);
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));

        ResponseEntity<Void> response = controller.acceptInvitation(roomId, invitationId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(roomMemberService).joinPrivateRoomViaInvitation(room, caller);
        verify(messageBroadcastService).broadcastMembership(room, caller, "MEMBER_JOINED");
    }

    @Test
    void acceptInvitation_shouldThrowNotFound_whenInvitationMissing() {
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.acceptInvitation(roomId, invitationId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invitation not found");
    }

    @Test
    void acceptInvitation_shouldThrowNotFound_whenInvitationBelongsToDifferentRoom() {
        RoomInvitation invitation = invitation(foreignRoom(), otherUser("inviter"), caller);
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> controller.acceptInvitation(roomId, invitationId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does not belong to room");

        verify(roomMemberService, never()).joinPrivateRoomViaInvitation(room, caller);
    }

    @Test
    void acceptInvitation_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.acceptInvitation(roomId, invitationId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(caller.getEmail());
    }

    // --- declineOrCancelInvitation ---

    @Test
    void declineOrCancel_shouldDelete_whenCallerIsInvitee() {
        RoomInvitation invitation = invitation(room, otherUser("inviter"), caller);
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.declineOrCancelInvitation(roomId, invitationId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(roomInvitationRepository).delete(invitation);
    }

    @Test
    void declineOrCancel_shouldDelete_whenCallerIsInviter() {
        RoomInvitation invitation = invitation(room, caller, otherUser("invitee"));
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.declineOrCancelInvitation(roomId, invitationId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(roomInvitationRepository).delete(invitation);
    }

    @ParameterizedTest(name = "role={0} may cancel any invitation")
    @EnumSource(
            value = RoomRole.class,
            names = {"OWNER", "ADMIN"})
    void declineOrCancel_shouldDelete_whenCallerIsRoomOwnerOrAdmin(RoomRole role) {
        RoomInvitation invitation = invitation(room, otherUser("inviter"), otherUser("invitee"));
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.of(memberWithRole(role)));

        ResponseEntity<Void> response = controller.declineOrCancelInvitation(roomId, invitationId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(roomInvitationRepository).delete(invitation);
    }

    @Test
    void declineOrCancel_shouldThrowForbidden_whenCallerIsUnrelatedMember() {
        RoomInvitation invitation = invitation(room, otherUser("inviter"), otherUser("invitee"));
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));
        when(roomMemberRepository.findById(callerMemberId())).thenReturn(Optional.of(memberWithRole(RoomRole.MEMBER)));

        assertThatThrownBy(() -> controller.declineOrCancelInvitation(roomId, invitationId, principal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Not allowed to modify");

        verify(roomInvitationRepository, never()).delete(invitation);
    }

    @Test
    void declineOrCancel_shouldThrowNotFound_whenInvitationMissing() {
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.declineOrCancelInvitation(roomId, invitationId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invitation not found");
    }

    @Test
    void declineOrCancel_shouldThrowNotFound_whenInvitationBelongsToDifferentRoom() {
        RoomInvitation invitation = invitation(foreignRoom(), otherUser("inviter"), caller);
        when(roomInvitationRepository.findById(invitationId)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> controller.declineOrCancelInvitation(roomId, invitationId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does not belong to room");

        verify(roomInvitationRepository, never()).delete(invitation);
    }

    // --- helpers ---

    private User stubInvitee(UUID inviteeId) {
        User invitee = User.builder().id(inviteeId).username("invitee").build();
        when(userRepository.findById(inviteeId)).thenReturn(Optional.of(invitee));
        return invitee;
    }

    private RoomMemberId callerMemberId() {
        return new RoomMemberId(room.getId(), caller.getId());
    }

    private RoomMember memberWithRole(RoomRole role) {
        return RoomMember.builder().room(room).user(caller).role(role).build();
    }

    private RoomInvitation invitation(Room invRoom, User inviter, User invitee) {
        return RoomInvitation.builder()
                .id(invitationId)
                .room(invRoom)
                .inviter(inviter)
                .invitee(invitee)
                .build();
    }

    private User otherUser(String username) {
        return User.builder().id(UUID.randomUUID()).username(username).build();
    }

    private Room foreignRoom() {
        return Room.builder()
                .id(UUID.randomUUID())
                .name("other-room")
                .visibility(RoomVisibility.PRIVATE)
                .owner(otherUser("owner"))
                .build();
    }
}
