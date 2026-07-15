package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.service.PresenceVisibilityService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PresenceVisibilityService} — the R1-65 visibility rule: a caller may see a
 * target's presence only when they are the same user, accepted friends, or share a room.
 */
@ExtendWith(MockitoExtension.class)
class PresenceVisibilityServiceTest {

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @InjectMocks
    private PresenceVisibilityService presenceVisibilityService;

    private UUID caller;
    private UUID target;

    @BeforeEach
    void setUp() {
        caller = UUID.randomUUID();
        target = UUID.randomUUID();
    }

    @Test
    void canView_returnsTrueWithoutQueryingRepositories_whenCallerIsTarget() {
        assertTrue(presenceVisibilityService.canView(caller, caller));
        verifyNoInteractions(friendshipRepository, roomMemberRepository);
    }

    @Test
    void canView_returnsTrue_whenUsersAreAcceptedFriends() {
        when(friendshipRepository.existsAcceptedBetween(caller, target)).thenReturn(true);

        assertTrue(presenceVisibilityService.canView(caller, target));
    }

    @Test
    void canView_returnsTrue_whenUsersShareRoomButAreNotFriends() {
        when(friendshipRepository.existsAcceptedBetween(caller, target)).thenReturn(false);
        when(roomMemberRepository.existsSharedRoom(caller, target)).thenReturn(true);

        assertTrue(presenceVisibilityService.canView(caller, target));
    }

    @Test
    void canView_returnsFalse_whenUsersAreNeitherFriendsNorShareRoom() {
        when(friendshipRepository.existsAcceptedBetween(caller, target)).thenReturn(false);
        when(roomMemberRepository.existsSharedRoom(caller, target)).thenReturn(false);

        assertFalse(presenceVisibilityService.canView(caller, target));
    }

    @Test
    void visibleAmong_returnsOnlyFriendsCoMembersAndSelf_filteringOutUnrelatedTargets() {
        UUID friend = UUID.randomUUID();
        UUID coMember = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(friendshipRepository.findAcceptedFriendIds(caller)).thenReturn(Set.of(friend));
        when(roomMemberRepository.findCoMemberUserIds(caller)).thenReturn(Set.of(coMember));

        Set<UUID> visible = presenceVisibilityService.visibleAmong(caller, List.of(friend, coMember, caller, stranger));

        assertThat(visible).containsExactlyInAnyOrder(friend, coMember, caller);
    }
}
