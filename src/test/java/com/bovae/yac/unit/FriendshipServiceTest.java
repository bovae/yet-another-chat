package com.bovae.yac.unit;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.NotificationEvent;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FriendshipService}.
 *
 * <p>Validates Correctness Properties: CP 10.
 * <p>Requirements: 5.1, 5.2, 14.2, 14.6, 14.9, 14.10.
 */
@ExtendWith(MockitoExtension.class)
class FriendshipServiceTest {

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private UserBanRepository userBanRepository;

    @Mock
    private NotificationService notificationService;

    @Captor
    private ArgumentCaptor<NotificationEvent> eventCaptor;

    @InjectMocks
    private FriendshipService friendshipService;

    private User userA;
    private User userB;
    private User userC;

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

        userC = User.builder()
                .id(UUID.randomUUID())
                .email("charlie@test.com")
                .username("charlie")
                .passwordHash("$2a$10$hash")
                .build();
    }

    // -----------------------------------------------------------------------
    // Full lifecycle: send (PENDING) → accept (ACCEPTED) → remove (deleted)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 10: Full friendship lifecycle — send request creates PENDING,
     * accept transitions to ACCEPTED, remove deletes the record.
     */
    @Test
    void fullLifecycle_sendAcceptRemove() {
        UUID friendshipId = UUID.randomUUID();

        // --- send ---
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.empty());
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(false);
        when(userBanRepository.existsByBlockerAndBlocked(userB, userA)).thenReturn(false);

        Friendship pending = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.PENDING)
                .build();
        when(friendshipRepository.save(any(Friendship.class))).thenReturn(pending);

        Friendship sent = friendshipService.sendFriendRequest(userA, userB, "Hi!");
        assertThat(sent.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(sent.getRequester()).isEqualTo(userA);
        assertThat(sent.getRecipient()).isEqualTo(userB);

        // --- accept ---
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(pending));

        Friendship accepted = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.ACCEPTED)
                .build();
        when(friendshipRepository.save(any(Friendship.class))).thenReturn(accepted);

        Friendship result = friendshipService.acceptFriendRequest(friendshipId, userB);
        assertThat(result.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);

        // --- remove ---
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(accepted));

        friendshipService.removeFriend(friendshipId, userA);
        verify(friendshipRepository).delete(accepted);
    }

    // -----------------------------------------------------------------------
    // Full lifecycle: send (PENDING) → decline (DECLINED)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 10: Friendship lifecycle — send request creates PENDING,
     * decline transitions to DECLINED.
     */
    @Test
    void fullLifecycle_sendDecline() {
        UUID friendshipId = UUID.randomUUID();

        // --- send ---
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.empty());
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(false);
        when(userBanRepository.existsByBlockerAndBlocked(userB, userA)).thenReturn(false);

        Friendship pending = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.PENDING)
                .build();
        when(friendshipRepository.save(any(Friendship.class))).thenReturn(pending);

        Friendship sent = friendshipService.sendFriendRequest(userA, userB, null);
        assertThat(sent.getStatus()).isEqualTo(FriendshipStatus.PENDING);

        // --- decline: now deletes the row so the pair can re-request (R1-30) ---
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(pending));

        Friendship result = friendshipService.declineFriendRequest(friendshipId, userB);

        verify(friendshipRepository).delete(pending);
        assertThat(result.getId()).isEqualTo(friendshipId);
    }

    // -----------------------------------------------------------------------
    // Live WS notifications on send/accept (R2-03)
    // -----------------------------------------------------------------------

    /** Validates R2-03: a sent request pushes FRIEND_REQUEST_CREATED to the recipient. */
    @Test
    void sendFriendRequest_broadcastsCreatedEventToRecipient() {
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.empty());
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(false);
        when(userBanRepository.existsByBlockerAndBlocked(userB, userA)).thenReturn(false);
        when(friendshipRepository.save(any(Friendship.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        friendshipService.sendFriendRequest(userA, userB, "Hi!");

        verify(notificationService).broadcastNotification(eq(userB), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("FRIEND_REQUEST_CREATED");
    }

    /** Validates R2-03: accepting pushes FRIEND_REQUEST_ACCEPTED back to the original requester. */
    @Test
    void acceptFriendRequest_broadcastsAcceptedEventToRequester() {
        UUID friendshipId = UUID.randomUUID();
        Friendship pending = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.PENDING)
                .build();
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(pending));
        when(friendshipRepository.save(any(Friendship.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        friendshipService.acceptFriendRequest(friendshipId, userB);

        verify(notificationService).broadcastNotification(eq(userA), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("FRIEND_REQUEST_ACCEPTED");
    }

    // -----------------------------------------------------------------------
    // sendFriendRequest with existing UserBan throws ForbiddenException
    // -----------------------------------------------------------------------

    /**
     * Validates CP 10: sendFriendRequest with an existing UserBan (blocker→blocked)
     * throws ForbiddenException.
     */
    @Test
    void sendFriendRequest_withUserBanBlockerToBlocked_throwsForbiddenException() {
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.empty());
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(true);

        assertThatThrownBy(() -> friendshipService.sendFriendRequest(userA, userB, "Hi"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("user ban");

        verify(friendshipRepository, never()).save(any());
    }

    /**
     * Validates CP 10: sendFriendRequest with an existing UserBan (blocked→blocker)
     * throws ForbiddenException.
     */
    @Test
    void sendFriendRequest_withUserBanBlockedToBlocker_throwsForbiddenException() {
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.empty());
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(false);
        when(userBanRepository.existsByBlockerAndBlocked(userB, userA)).thenReturn(true);

        assertThatThrownBy(() -> friendshipService.sendFriendRequest(userA, userB, "Hi"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("user ban");

        verify(friendshipRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // sendFriendRequest with existing friendship throws ConflictException
    // -----------------------------------------------------------------------

    /**
     * Validates Requirement 14.2: sendFriendRequest with an existing friendship
     * (forward direction) throws ConflictException.
     */
    @Test
    void sendFriendRequest_withExistingFriendshipForward_throwsConflictException() {
        Friendship existing = Friendship.builder()
                .id(UUID.randomUUID())
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.ACCEPTED)
                .build();
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> friendshipService.sendFriendRequest(userA, userB, "Hi"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        verify(friendshipRepository, never()).save(any());
    }

    /**
     * Validates Requirement 14.2: sendFriendRequest with an existing friendship
     * (reverse direction) throws ConflictException.
     */
    @Test
    void sendFriendRequest_withExistingFriendshipReverse_throwsConflictException() {
        Friendship existing = Friendship.builder()
                .id(UUID.randomUUID())
                .requester(userB)
                .recipient(userA)
                .status(FriendshipStatus.PENDING)
                .build();
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> friendshipService.sendFriendRequest(userA, userB, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        verify(friendshipRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // sendFriendRequest to self throws ConflictException
    // -----------------------------------------------------------------------

    /**
     * Validates Requirement 14.6: sendFriendRequest to self throws ConflictException.
     */
    @Test
    void sendFriendRequest_toSelf_throwsConflictException() {
        assertThatThrownBy(() -> friendshipService.sendFriendRequest(userA, userA, "Hi me"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("yourself");

        verify(friendshipRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Only recipient can accept (non-recipient throws ForbiddenException)
    // -----------------------------------------------------------------------

    /**
     * Validates Requirement 14.9: Only the recipient can accept a friend request.
     * Non-recipient (requester) attempting to accept throws ForbiddenException.
     */
    @Test
    void acceptFriendRequest_byNonRecipient_throwsForbiddenException() {
        UUID friendshipId = UUID.randomUUID();
        Friendship pending = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.PENDING)
                .build();
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(pending));

        // userA is the requester, not the recipient — should be rejected
        assertThatThrownBy(() -> friendshipService.acceptFriendRequest(friendshipId, userA))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the recipient");

        verify(friendshipRepository, never()).save(any());
    }

    /**
     * Validates Requirement 14.9: A third-party user cannot accept a friend request.
     */
    @Test
    void acceptFriendRequest_byThirdParty_throwsForbiddenException() {
        UUID friendshipId = UUID.randomUUID();
        Friendship pending = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.PENDING)
                .build();
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> friendshipService.acceptFriendRequest(friendshipId, userC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the recipient");

        verify(friendshipRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Only recipient can decline (non-recipient throws ForbiddenException)
    // -----------------------------------------------------------------------

    /**
     * Validates Requirement 14.9: Only the recipient can decline a friend request.
     * Non-recipient attempting to decline throws ForbiddenException.
     */
    @Test
    void declineFriendRequest_byNonRecipient_throwsForbiddenException() {
        UUID friendshipId = UUID.randomUUID();
        Friendship pending = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.PENDING)
                .build();
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> friendshipService.declineFriendRequest(friendshipId, userA))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the recipient");

        verify(friendshipRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Only participants can remove friendship
    // -----------------------------------------------------------------------

    /**
     * Validates Requirement 14.10: Only participants of the friendship can remove it.
     * A non-participant throws ForbiddenException.
     */
    @Test
    void removeFriend_byNonParticipant_throwsForbiddenException() {
        UUID friendshipId = UUID.randomUUID();
        Friendship accepted = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.ACCEPTED)
                .build();
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> friendshipService.removeFriend(friendshipId, userC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only participants");

        verify(friendshipRepository, never()).delete(any());
    }

    /**
     * Validates CP 10: Either participant (requester) can remove the friendship.
     */
    @Test
    void removeFriend_byRequester_succeeds() {
        UUID friendshipId = UUID.randomUUID();
        Friendship accepted = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.ACCEPTED)
                .build();
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(accepted));

        friendshipService.removeFriend(friendshipId, userA);

        verify(friendshipRepository).delete(accepted);
    }

    /**
     * Validates CP 10: Either participant (recipient) can remove the friendship.
     */
    @Test
    void removeFriend_byRecipient_succeeds() {
        UUID friendshipId = UUID.randomUUID();
        Friendship accepted = Friendship.builder()
                .id(friendshipId)
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.ACCEPTED)
                .build();
        when(friendshipRepository.findById(friendshipId)).thenReturn(Optional.of(accepted));

        friendshipService.removeFriend(friendshipId, userB);

        verify(friendshipRepository).delete(accepted);
    }
}
