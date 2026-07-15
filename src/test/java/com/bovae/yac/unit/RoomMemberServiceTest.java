package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomMemberService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RoomMemberService}.
 *
 * <p>Validates Correctness Properties: CP 14, CP 15.
 * <p>Requirements: 3.6, 3.7, 3.8, 14.1.
 */
@ExtendWith(MockitoExtension.class)
class RoomMemberServiceTest {

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private RoomBanRepository roomBanRepository;

    @Mock
    private RoomInvitationRepository roomInvitationRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private RoomMemberService roomMemberService;

    private User userA;
    private Room publicRoom;
    private Room privateRoom;

    @BeforeEach
    void setUp() {
        userA = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("$2a$10$hashedpassword")
                .build();

        publicRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("public-room")
                .description("A public room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(User.builder().id(UUID.randomUUID()).build())
                .nextWatermark(1L)
                .build();

        privateRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("private-room")
                .description("A private room")
                .visibility(RoomVisibility.PRIVATE)
                .owner(User.builder().id(UUID.randomUUID()).build())
                .nextWatermark(1L)
                .build();
    }

    // ── joinPublicRoom ──────────────────────────────────────────────────

    /**
     * Validates CP 14: A non-banned user can join a public room and receives MEMBER role.
     */
    @Test
    void joinPublicRoom_nonBannedUser_succeeds() {
        when(roomBanRepository.existsByRoomAndUser(publicRoom, userA)).thenReturn(false);
        when(roomMemberRepository.existsByRoomAndUser(publicRoom, userA)).thenReturn(false);
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomMember result = roomMemberService.joinPublicRoom(publicRoom, userA);

        assertThat(result.getRoom()).isEqualTo(publicRoom);
        assertThat(result.getUser()).isEqualTo(userA);
        assertThat(result.getRole()).isEqualTo(RoomRole.MEMBER);
        verify(roomMemberRepository).save(any(RoomMember.class));
    }

    /**
     * Validates CP 14: A banned user cannot join a public room — throws ForbiddenException.
     */
    @Test
    void joinPublicRoom_bannedUser_throwsForbiddenException() {
        when(roomBanRepository.existsByRoomAndUser(publicRoom, userA)).thenReturn(true);

        assertThatThrownBy(() -> roomMemberService.joinPublicRoom(publicRoom, userA))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("banned");

        verify(roomMemberRepository, never()).save(any());
    }

    /**
     * Validates CP 14: Joining a non-public room directly throws ForbiddenException.
     */
    @Test
    void joinPublicRoom_privateRoom_throwsForbiddenException() {
        assertThatThrownBy(() -> roomMemberService.joinPublicRoom(privateRoom, userA))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("public");

        verify(roomMemberRepository, never()).save(any());
    }

    /**
     * Validates Requirement 14.1: Joining a room the user is already a member of throws ConflictException.
     */
    @Test
    void joinPublicRoom_alreadyMember_throwsConflictException() {
        when(roomBanRepository.existsByRoomAndUser(publicRoom, userA)).thenReturn(false);
        when(roomMemberRepository.existsByRoomAndUser(publicRoom, userA)).thenReturn(true);

        assertThatThrownBy(() -> roomMemberService.joinPublicRoom(publicRoom, userA))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already a member");

        verify(roomMemberRepository, never()).save(any());
    }

    // ── joinPrivateRoomViaInvitation ────────────────────────────────────

    /**
     * Validates CP 14: A user with a valid invitation can join a private room,
     * and the invitation is deleted after joining.
     */
    @Test
    void joinPrivateRoomViaInvitation_validInvitation_succeedsAndDeletesInvitation() {
        RoomInvitation invitation = RoomInvitation.builder()
                .id(UUID.randomUUID())
                .room(privateRoom)
                .invitee(userA)
                .build();

        when(roomInvitationRepository.findByRoomAndInvitee(privateRoom, userA)).thenReturn(Optional.of(invitation));
        when(roomMemberRepository.existsByRoomAndUser(privateRoom, userA)).thenReturn(false);
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomMember result = roomMemberService.joinPrivateRoomViaInvitation(privateRoom, userA);

        assertThat(result.getRoom()).isEqualTo(privateRoom);
        assertThat(result.getUser()).isEqualTo(userA);
        assertThat(result.getRole()).isEqualTo(RoomRole.MEMBER);
        verify(roomMemberRepository).save(any(RoomMember.class));
        verify(roomInvitationRepository).delete(invitation);
    }

    /**
     * Validates CP 14: Joining a private room without an invitation throws ForbiddenException.
     */
    @Test
    void joinPrivateRoomViaInvitation_noInvitation_throwsForbiddenException() {
        when(roomInvitationRepository.findByRoomAndInvitee(privateRoom, userA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomMemberService.joinPrivateRoomViaInvitation(privateRoom, userA))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("No invitation");

        verify(roomMemberRepository, never()).save(any());
    }

    /**
     * Validates Requirement 14.1: Joining a private room via invitation when already a member
     * throws ConflictException.
     */
    @Test
    void joinPrivateRoomViaInvitation_alreadyMember_throwsConflictException() {
        RoomInvitation invitation = RoomInvitation.builder()
                .id(UUID.randomUUID())
                .room(privateRoom)
                .invitee(userA)
                .build();

        when(roomInvitationRepository.findByRoomAndInvitee(privateRoom, userA)).thenReturn(Optional.of(invitation));
        when(roomMemberRepository.existsByRoomAndUser(privateRoom, userA)).thenReturn(true);

        assertThatThrownBy(() -> roomMemberService.joinPrivateRoomViaInvitation(privateRoom, userA))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already a member");

        verify(roomMemberRepository, never()).save(any());
    }

    // ── leaveRoom ───────────────────────────────────────────────────────

    /**
     * Validates CP 15: A non-owner member can leave a room, and the member record is removed.
     */
    @Test
    void leaveRoom_nonOwnerMember_removesMemberRecord() {
        RoomMember member = RoomMember.builder()
                .room(publicRoom)
                .user(userA)
                .role(RoomRole.MEMBER)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(publicRoom.getId(), userA.getId())))
                .thenReturn(Optional.of(member));

        roomMemberService.leaveRoom(publicRoom, userA);

        verify(roomMemberRepository).delete(member);
    }

    /**
     * Validates CP 15: The room owner cannot leave — throws ForbiddenException.
     */
    @Test
    void leaveRoom_owner_throwsForbiddenException() {
        User ownerUser = publicRoom.getOwner();
        RoomMember ownerMember = RoomMember.builder()
                .room(publicRoom)
                .user(ownerUser)
                .role(RoomRole.OWNER)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(publicRoom.getId(), ownerUser.getId())))
                .thenReturn(Optional.of(ownerMember));

        assertThatThrownBy(() -> roomMemberService.leaveRoom(publicRoom, ownerUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Owner cannot leave");

        verify(roomMemberRepository, never()).delete(any(RoomMember.class));
    }

    /**
     * Validates CP 15: Leaving a room the user is not a member of throws ForbiddenException.
     */
    @Test
    void leaveRoom_nonMember_throwsForbiddenException() {
        when(roomMemberRepository.findById(new RoomMemberId(publicRoom.getId(), userA.getId())))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomMemberService.leaveRoom(publicRoom, userA))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not a member");

        verify(roomMemberRepository, never()).delete(any(RoomMember.class));
    }
}
