package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.FriendshipApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.FriendshipDto;
import com.bovae.yac.model.dto.SendFriendRequest;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
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

/** Unit tests for {@link FriendshipApiController}. */
@ExtendWith(MockitoExtension.class)
class FriendshipApiControllerTest {

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private FriendshipApiController controller;

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
    void listFriends_shouldReturnFriends_whenResolved() {
        List<FriendshipDto> friends = List.of();
        when(friendshipService.listFriends(user)).thenReturn(friends);

        ResponseEntity<List<FriendshipDto>> response = controller.listFriends(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(friends);
    }

    @Test
    void sendFriendRequest_shouldReturnCreatedAndDelegate_whenRecipientFound() {
        User recipient = User.builder().id(UUID.randomUUID()).username("bob").build();
        SendFriendRequest request = new SendFriendRequest("bob", "hi there");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(recipient));

        ResponseEntity<Void> response = controller.sendFriendRequest(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(friendshipService).sendFriendRequest(user, recipient, "hi there");
    }

    @Test
    void sendFriendRequest_shouldThrowNotFound_whenRecipientUsernameUnknown() {
        SendFriendRequest request = new SendFriendRequest("ghost", null);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.sendFriendRequest(request, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ghost");

        verify(friendshipService, never()).sendFriendRequest(user, null, null);
    }

    @Test
    void acceptFriendRequest_shouldReturnOkAndDelegate_whenResolved() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.acceptFriendRequest(id, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(friendshipService).acceptFriendRequest(id, user);
    }

    @Test
    void declineFriendRequest_shouldReturnOkAndDelegate_whenResolved() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.declineFriendRequest(id, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(friendshipService).declineFriendRequest(id, user);
    }

    @Test
    void removeFriend_shouldReturnNoContentAndDelegate_whenResolved() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.removeFriend(id, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(friendshipService).removeFriend(id, user);
    }

    @Test
    void incomingRequests_shouldReturnPendingIncoming_whenResolved() {
        List<FriendshipDto> incoming = List.of();
        when(friendshipService.listPendingIncoming(user)).thenReturn(incoming);

        ResponseEntity<List<FriendshipDto>> response = controller.incomingRequests(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(incoming);
    }

    @Test
    void outgoingRequests_shouldReturnPendingOutgoing_whenResolved() {
        List<FriendshipDto> outgoing = List.of();
        when(friendshipService.listPendingOutgoing(user)).thenReturn(outgoing);

        ResponseEntity<List<FriendshipDto>> response = controller.outgoingRequests(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(outgoing);
    }

    @Test
    void listFriends_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listFriends(principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(friendshipService, never()).listFriends(user);
    }
}
