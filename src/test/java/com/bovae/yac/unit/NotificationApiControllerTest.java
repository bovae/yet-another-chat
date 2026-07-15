package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.NotificationApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.MyRoomEntry;
import com.bovae.yac.model.dto.NotificationSummary;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
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

/** Unit tests for {@link NotificationApiController}. */
@ExtendWith(MockitoExtension.class)
class NotificationApiControllerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private RoomInvitationRepository roomInvitationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private NotificationApiController controller;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("caller@test.com")
                .username("caller")
                .displayName("Caller")
                .build();

        lenient().when(principal.getName()).thenReturn(user.getEmail());
        lenient().when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test
    void summary_shouldAggregateUnreadFriendAndInvitationCounts_whenResolved() {
        when(roomService.listUserRoomsWithUnread(user)).thenReturn(List.of(roomEntry(3), roomEntry(2)));
        when(friendshipRepository.countByRecipientAndStatus(user, FriendshipStatus.PENDING))
                .thenReturn(4L);
        when(roomInvitationRepository.countByInvitee(user)).thenReturn(2L);

        ResponseEntity<NotificationSummary> response = controller.summary(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        NotificationSummary body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.unreadTotal()).isEqualTo(5);
        assertThat(body.pendingFriendRequests()).isEqualTo(4);
        assertThat(body.pendingInvitations()).isEqualTo(2);
    }

    @Test
    void summary_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.summary(principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(roomService, never()).listUserRoomsWithUnread(user);
    }

    private MyRoomEntry roomEntry(int unreadCount) {
        return new MyRoomEntry(UUID.randomUUID(), "room", RoomVisibility.PUBLIC, unreadCount, null, null);
    }
}
