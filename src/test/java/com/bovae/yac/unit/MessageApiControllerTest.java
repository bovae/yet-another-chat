package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.MessageApiController;
import com.bovae.yac.model.dto.MessageDeletedEvent;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for {@link MessageApiController}.
 *
 * <p>Validates Requirements: 7.2, 7.3.
 */
@ExtendWith(MockitoExtension.class)
class MessageApiControllerTest {

    @Mock
    private MessageService messageService;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private Principal principal;

    @InjectMocks
    private MessageApiController controller;

    private User user;
    private Room room;
    private UUID roomId;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        messageId = UUID.randomUUID();

        user = User.builder()
                .id(UUID.randomUUID())
                .email("user@test.com")
                .username("testuser")
                .displayName("Test User")
                .passwordHash("hashed")
                .build();

        room = Room.builder()
                .id(roomId)
                .name("test-room")
                .description("A test room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(user)
                .build();

        when(principal.getName()).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(roomService.getRoomById(roomId)).thenReturn(room);
    }

    @Test
    void deleteMessage_broadcastsDeletionEventToRoomTopic() {
        ResponseEntity<Void> response = controller.deleteMessage(roomId, messageId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(messageService).deleteMessage(messageId, user, room);

        ArgumentCaptor<MessageDeletedEvent> eventCaptor = ArgumentCaptor.forClass(MessageDeletedEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/room." + roomId), eventCaptor.capture());

        MessageDeletedEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("MESSAGE_DELETED");
        assertThat(event.messageId()).isEqualTo(messageId);
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.deletedBy()).isEqualTo(user.getId());
    }
}
