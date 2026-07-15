package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.service.ModerationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ModerationService}.
 *
 * <p>Validates Correctness Property: CP 16.
 * <p>Requirements: 3.9, 3.10, 3.11, 3.12, 14.8.
 */
@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private RoomBanRepository roomBanRepository;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private ModerationService moderationService;

    private User ownerUser;
    private User adminUser;
    private User memberUser;
    private Room room;

    @BeforeEach
    void setUp() {
        ownerUser = User.builder()
                .id(UUID.randomUUID())
                .email("owner@test.com")
                .username("owner")
                .passwordHash("$2a$10$hashedpassword")
                .build();

        adminUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .username("admin")
                .passwordHash("$2a$10$hashedpassword")
                .build();

        memberUser = User.builder()
                .id(UUID.randomUUID())
                .email("member@test.com")
                .username("member")
                .passwordHash("$2a$10$hashedpassword")
                .build();

        room = Room.builder()
                .id(UUID.randomUUID())
                .name("test-room")
                .description("A test room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(ownerUser)
                .nextWatermark(1L)
                .build();
    }

    /**
     * Validates CP 16: kickMember creates a RoomBan record and removes the
     * RoomMember when the acting user has admin privileges.
     */
    @Test
    void kickMember_byAdmin_createsBanAndRemovesMember() {
        RoomMember targetMember = stubAdminActorAndMemberTarget();

        moderationService.kickMember(room, adminUser, memberUser);

        ArgumentCaptor<RoomBan> banCaptor = ArgumentCaptor.forClass(RoomBan.class);
        verify(roomBanRepository).save(banCaptor.capture());
        RoomBan savedBan = banCaptor.getValue();
        assertThat(savedBan.getRoom()).isEqualTo(room);
        assertThat(savedBan.getUser()).isEqualTo(memberUser);
        assertThat(savedBan.getBannedBy()).isEqualTo(adminUser);

        verify(roomMemberRepository).delete(targetMember);
    }

    /**
     * Validates CP 16: kickMember targeting the room owner throws ForbiddenException
     * because the owner cannot be kicked from their own room.
     */
    @Test
    void kickMember_targetIsOwner_throwsForbiddenException() {
        stubAdminActorAndOwnerTarget();

        assertThatThrownBy(() -> moderationService.kickMember(room, adminUser, ownerUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Cannot kick the room owner");

        verify(roomBanRepository, never()).save(any());
        verify(roomMemberRepository, never()).delete(any(RoomMember.class));
    }

    /**
     * Validates CP 16: grantAdminRole succeeds only when the acting user is the
     * room Owner. The target member's role is updated to ADMIN.
     */
    @Test
    void grantAdminRole_byOwner_updatesTargetRoleToAdmin() {
        RoomMember ownerMember = RoomMember.builder()
                .room(room)
                .user(ownerUser)
                .role(RoomRole.OWNER)
                .build();
        RoomMember targetMember = RoomMember.builder()
                .room(room)
                .user(memberUser)
                .role(RoomRole.MEMBER)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), ownerUser.getId())))
                .thenReturn(Optional.of(ownerMember));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), memberUser.getId())))
                .thenReturn(Optional.of(targetMember));

        moderationService.grantAdminRole(room, ownerUser, memberUser);

        assertThat(targetMember.getRole()).isEqualTo(RoomRole.ADMIN);
        verify(roomMemberRepository).save(targetMember);
    }

    /**
     * Validates CP 16: grantAdminRole by a non-owner (e.g. ADMIN) throws
     * ForbiddenException because only the Owner can grant admin role.
     */
    @Test
    void grantAdminRole_byNonOwner_throwsForbiddenException() {
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));

        assertThatThrownBy(() -> moderationService.grantAdminRole(room, adminUser, memberUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the room owner can grant admin role");

        verify(roomMemberRepository, never()).save(any());
    }

    /**
     * Validates CP 16: revokeAdminRole targeting the Owner throws ForbiddenException
     * because the owner cannot be demoted.
     */
    @Test
    void revokeAdminRole_targetIsOwner_throwsForbiddenException() {
        stubAdminActorAndOwnerTarget();

        assertThatThrownBy(() -> moderationService.revokeAdminRole(room, adminUser, ownerUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Cannot demote the room owner");

        verify(roomMemberRepository, never()).save(any());
    }

    /**
     * Validates CP 16: deleteMessage by an admin permanently removes the message
     * from the repository.
     */
    @Test
    void deleteMessage_byAdmin_removesMessage() {
        UUID messageId = UUID.randomUUID();
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();
        Message message = Message.builder()
                .id(messageId)
                .room(room)
                .sender(memberUser)
                .content("some message")
                .watermark(1L)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        moderationService.deleteMessage(room, adminUser, messageId);

        verify(messageRepository).delete(message);
    }

    // --- banUserFromRoom idempotency ---

    /**
     * Validates CP 16: banUserFromRoom is idempotent — when the target is already banned
     * it returns without persisting a duplicate ban or removing membership again.
     */
    @Test
    void banUserFromRoom_alreadyBanned_returnsWithoutSavingDuplicate() {
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(roomBanRepository.existsByRoomAndUser(room, memberUser)).thenReturn(true);

        moderationService.banUserFromRoom(room, adminUser, memberUser);

        verify(roomBanRepository, never()).save(any());
        verify(roomMemberRepository, never()).delete(any(RoomMember.class));
    }

    // --- deleteMessage guards ---

    /**
     * Validates CP 16: deleteMessage throws ResourceNotFoundException when the message id
     * does not resolve to a stored message.
     */
    @Test
    void deleteMessage_messageNotFound_throwsResourceNotFoundException() {
        UUID messageId = UUID.randomUUID();
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.deleteMessage(room, adminUser, messageId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Message not found");

        verify(messageRepository, never()).delete(any(Message.class));
    }

    /**
     * Validates CP 16: deleteMessage throws ForbiddenException when the resolved message
     * belongs to a different room than the one the action is scoped to.
     */
    @Test
    void deleteMessage_messageBelongsToDifferentRoom_throwsForbiddenException() {
        UUID messageId = UUID.randomUUID();
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();
        Room otherRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("other-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(ownerUser)
                .nextWatermark(1L)
                .build();
        Message message = Message.builder()
                .id(messageId)
                .room(otherRoom)
                .sender(memberUser)
                .content("elsewhere")
                .watermark(1L)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> moderationService.deleteMessage(room, adminUser, messageId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("does not belong to this room");

        verify(messageRepository, never()).delete(any(Message.class));
    }

    // --- revokeAdminRole role matrix ---

    /**
     * Validates CP 16: the owner may demote an admin, whose role becomes MEMBER.
     */
    @Test
    void revokeAdminRole_ownerDemotesAdmin_setsRoleToMember() {
        RoomMember ownerMember = RoomMember.builder()
                .room(room)
                .user(ownerUser)
                .role(RoomRole.OWNER)
                .build();
        RoomMember targetMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), ownerUser.getId())))
                .thenReturn(Optional.of(ownerMember));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(targetMember));

        moderationService.revokeAdminRole(room, ownerUser, adminUser);

        assertThat(targetMember.getRole()).isEqualTo(RoomRole.MEMBER);
        verify(roomMemberRepository).save(targetMember);
    }

    /**
     * Validates CP 16: an admin may demote another admin, whose role becomes MEMBER.
     */
    @Test
    void revokeAdminRole_adminDemotesAnotherAdmin_setsRoleToMember() {
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();
        RoomMember targetMember = RoomMember.builder()
                .room(room)
                .user(memberUser)
                .role(RoomRole.ADMIN)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), memberUser.getId())))
                .thenReturn(Optional.of(targetMember));

        moderationService.revokeAdminRole(room, adminUser, memberUser);

        assertThat(targetMember.getRole()).isEqualTo(RoomRole.MEMBER);
        verify(roomMemberRepository).save(targetMember);
    }

    /**
     * Validates CP 16: an admin cannot demote a plain member — only another admin —
     * so the attempt throws ForbiddenException and persists nothing.
     */
    @Test
    void revokeAdminRole_adminDemotesNonAdminMember_throwsForbiddenException() {
        stubAdminActorAndMemberTarget();

        assertThatThrownBy(() -> moderationService.revokeAdminRole(room, adminUser, memberUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Admin can only demote another admin");

        verify(roomMemberRepository, never()).save(any());
    }

    /**
     * Validates CP 16: a plain member cannot revoke admin roles at all — the attempt
     * throws ForbiddenException and persists nothing.
     */
    @Test
    void revokeAdminRole_actorIsPlainMember_throwsForbiddenException() {
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(memberUser)
                .role(RoomRole.MEMBER)
                .build();
        RoomMember targetMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), memberUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(targetMember));

        assertThatThrownBy(() -> moderationService.revokeAdminRole(room, memberUser, adminUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only admins and owners can revoke admin role");

        verify(roomMemberRepository, never()).save(any());
    }

    // --- membership guard ---

    /**
     * Validates CP 16: moderation actions reject an acting user who is not a member of
     * the room with ResourceNotFoundException before any state change.
     */
    @Test
    void kickMember_actorNotAMember_throwsResourceNotFoundException() {
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.kickMember(room, adminUser, memberUser))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not a member of this room");

        verify(roomBanRepository, never()).save(any());
    }

    /**
     * Stubs the member lookups for an ADMIN actor acting on a plain MEMBER target,
     * returning the target member for per-test assertions.
     */
    private RoomMember stubAdminActorAndMemberTarget() {
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();
        RoomMember targetMember = RoomMember.builder()
                .room(room)
                .user(memberUser)
                .role(RoomRole.MEMBER)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), memberUser.getId())))
                .thenReturn(Optional.of(targetMember));
        return targetMember;
    }

    /**
     * Stubs the member lookups for an ADMIN actor acting on the room OWNER as target,
     * the shared arrangement for owner-protection guard tests.
     */
    private void stubAdminActorAndOwnerTarget() {
        RoomMember actorMember = RoomMember.builder()
                .room(room)
                .user(adminUser)
                .role(RoomRole.ADMIN)
                .build();
        RoomMember ownerMember = RoomMember.builder()
                .room(room)
                .user(ownerUser)
                .role(RoomRole.OWNER)
                .build();

        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), adminUser.getId())))
                .thenReturn(Optional.of(actorMember));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), ownerUser.getId())))
                .thenReturn(Optional.of(ownerMember));
    }
}
