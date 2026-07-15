package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.service.UserBanService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UserBanService}.
 *
 * <p>Validates Correctness Properties: CP 11.
 * <p>Requirements: 5.3, 5.4, 14.3, 14.5.
 */
@ExtendWith(MockitoExtension.class)
class UserBanServiceTest {

    @Mock
    private UserBanRepository userBanRepository;

    @Mock
    private FriendshipRepository friendshipRepository;

    @InjectMocks
    private UserBanService userBanService;

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

    // -----------------------------------------------------------------------
    // banUser creates UserBan and deletes any existing Friendship (CP 11)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 11, Requirement 5.3: banUser creates a UserBan record and
     * deletes any existing Friendship between the two users.
     */
    @Test
    void banUser_createsUserBanAndDeletesExistingFriendship() {
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(false);

        Friendship forwardFriendship = Friendship.builder()
                .id(UUID.randomUUID())
                .requester(userA)
                .recipient(userB)
                .status(FriendshipStatus.ACCEPTED)
                .build();
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.of(forwardFriendship));
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.empty());

        UserBan savedBan = UserBan.builder()
                .id(UUID.randomUUID())
                .blocker(userA)
                .blocked(userB)
                .build();
        when(userBanRepository.save(any(UserBan.class))).thenReturn(savedBan);

        UserBan result = userBanService.banUser(userA, userB);

        assertThat(result).isNotNull();
        assertThat(result.getBlocker()).isEqualTo(userA);
        assertThat(result.getBlocked()).isEqualTo(userB);

        // Verify the ban was saved with correct blocker/blocked
        ArgumentCaptor<UserBan> banCaptor = ArgumentCaptor.forClass(UserBan.class);
        verify(userBanRepository).save(banCaptor.capture());
        assertThat(banCaptor.getValue().getBlocker()).isEqualTo(userA);
        assertThat(banCaptor.getValue().getBlocked()).isEqualTo(userB);

        // Verify the forward friendship was deleted
        verify(friendshipRepository).delete(forwardFriendship);
    }

    /**
     * Validates CP 11: banUser deletes reverse-direction friendship if it exists.
     */
    @Test
    void banUser_deletesReverseFriendshipIfExists() {
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(false);

        Friendship reverseFriendship = Friendship.builder()
                .id(UUID.randomUUID())
                .requester(userB)
                .recipient(userA)
                .status(FriendshipStatus.ACCEPTED)
                .build();
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.of(reverseFriendship));

        UserBan savedBan = UserBan.builder()
                .id(UUID.randomUUID())
                .blocker(userA)
                .blocked(userB)
                .build();
        when(userBanRepository.save(any(UserBan.class))).thenReturn(savedBan);

        userBanService.banUser(userA, userB);

        verify(friendshipRepository).delete(reverseFriendship);
    }

    /**
     * Validates CP 11: banUser succeeds even when no friendship exists.
     */
    @Test
    void banUser_noExistingFriendship_succeeds() {
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(false);
        when(friendshipRepository.findByRequesterAndRecipient(userA, userB)).thenReturn(Optional.empty());
        when(friendshipRepository.findByRequesterAndRecipient(userB, userA)).thenReturn(Optional.empty());

        UserBan savedBan = UserBan.builder()
                .id(UUID.randomUUID())
                .blocker(userA)
                .blocked(userB)
                .build();
        when(userBanRepository.save(any(UserBan.class))).thenReturn(savedBan);

        UserBan result = userBanService.banUser(userA, userB);

        assertThat(result).isNotNull();
        verify(friendshipRepository, never()).delete(any(Friendship.class));
    }

    // -----------------------------------------------------------------------
    // unbanUser deletes the UserBan record (CP 11)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 11, Requirement 5.4: unbanUser deletes the UserBan record.
     */
    @Test
    void unbanUser_deletesUserBanRecord() {
        UserBan existingBan = UserBan.builder()
                .id(UUID.randomUUID())
                .blocker(userA)
                .blocked(userB)
                .build();
        when(userBanRepository.findByBlockerAndBlocked(userA, userB)).thenReturn(Optional.of(existingBan));

        userBanService.unbanUser(userA, userB);

        verify(userBanRepository).delete(existingBan);
    }

    /**
     * Validates CP 11: unbanUser with no existing ban throws ResourceNotFoundException.
     */
    @Test
    void unbanUser_noBanExists_throwsResourceNotFoundException() {
        when(userBanRepository.findByBlockerAndBlocked(userA, userB)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userBanService.unbanUser(userA, userB))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No ban found");
    }

    // -----------------------------------------------------------------------
    // Banning already-banned user throws ConflictException (Req 14.3)
    // -----------------------------------------------------------------------

    /**
     * Validates Requirement 14.3: banning a user who is already banned throws ConflictException.
     */
    @Test
    void banUser_alreadyBanned_throwsConflictException() {
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(true);

        assertThatThrownBy(() -> userBanService.banUser(userA, userB))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already banned");

        verify(userBanRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Banning self throws ConflictException (Req 14.5)
    // -----------------------------------------------------------------------

    /**
     * Validates Requirement 14.5: a user cannot ban themselves — throws ConflictException.
     */
    @Test
    void banUser_self_throwsConflictException() {
        assertThatThrownBy(() -> userBanService.banUser(userA, userA))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("yourself");

        verify(userBanRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // isBannedBy passes through the (blocker, blocked) existence check
    // -----------------------------------------------------------------------

    /**
     * Validates isBannedBy returns exactly the repository's existence result for the
     * (blocker, blocked) pair, confirming the argument order is preserved: isBannedBy
     * asks whether {@code blocked} is banned by {@code blocker}.
     */
    @ParameterizedTest(name = "existsByBlockerAndBlocked={0} -> isBannedBy={0}")
    @ValueSource(booleans = {true, false})
    void isBannedBy_returnsRepositoryExistenceResult(boolean banned) {
        when(userBanRepository.existsByBlockerAndBlocked(userA, userB)).thenReturn(banned);

        boolean result = userBanService.isBannedBy(userB, userA);

        assertThat(result).isEqualTo(banned);
    }
}
