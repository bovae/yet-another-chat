package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.DirectChatApiController;
import com.bovae.yac.controller.api.DirectChatApiController.DirectChatRequest;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.DirectChatDto;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
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

/** Unit tests for {@link DirectChatApiController}. */
@ExtendWith(MockitoExtension.class)
class DirectChatApiControllerTest {

    @Mock
    private DirectChatService directChatService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private DirectChatApiController controller;

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
    void createOrGetDirectChat_shouldReturnRoom_whenBothUsersResolved() {
        User other = User.builder().id(UUID.randomUUID()).username("bob").build();
        DirectChatRequest request = new DirectChatRequest(other.getId());
        RoomDto room = roomDto();
        when(userRepository.findById(other.getId())).thenReturn(Optional.of(other));
        when(directChatService.getOrCreateDirectChat(user, other)).thenReturn(room);

        ResponseEntity<RoomDto> response = controller.createOrGetDirectChat(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(room);
    }

    @Test
    void createOrGetDirectChat_shouldThrowNotFound_whenOtherUserMissing() {
        UUID otherId = UUID.randomUUID();
        DirectChatRequest request = new DirectChatRequest(otherId);
        when(userRepository.findById(otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.createOrGetDirectChat(request, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(otherId.toString());

        verify(directChatService, never()).getOrCreateDirectChat(user, null);
    }

    @Test
    void getOrCreateSavedMessages_shouldReturnRoom_whenResolved() {
        RoomDto room = roomDto();
        when(directChatService.getOrCreateSavedMessages(user)).thenReturn(room);

        ResponseEntity<RoomDto> response = controller.getOrCreateSavedMessages(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(room);
    }

    @Test
    void listDirectChats_shouldReturnChats_whenResolved() {
        List<DirectChatDto> chats = List.of();
        when(directChatService.listDirectChats(user)).thenReturn(chats);

        ResponseEntity<List<DirectChatDto>> response = controller.listDirectChats(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(chats);
    }

    @Test
    void listDirectChats_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listDirectChats(principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(directChatService, never()).listDirectChats(user);
    }

    private RoomDto roomDto() {
        return new RoomDto(UUID.randomUUID(), "dm", null, null, user.getId(), "caller", 1L, null);
    }
}
