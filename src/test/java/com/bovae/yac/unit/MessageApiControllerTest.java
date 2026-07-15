package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.MessageApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.EditMessageRequest;
import com.bovae.yac.model.dto.MessageDeletedEvent;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.entity.Message;
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
import java.util.List;
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

        lenient().when(principal.getName()).thenReturn(user.getEmail());
        lenient().when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        lenient().when(roomService.getRoomById(roomId)).thenReturn(room);
    }

    // --- getMessages ---

    @Test
    void getMessages_shouldReturnBackwardHistory_whenAfterIsNull() {
        MessagePage page = new MessagePage(List.of(), null, false);
        when(messageService.getMessageHistory(room, null, 50)).thenReturn(page);

        ResponseEntity<MessagePage> response = controller.getMessages(roomId, null, null, 50, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(page);
        verify(roomMemberService).requireCanRead(room, user);
        verify(messageService, never()).getMessagesSince(room, null, 50);
    }

    @Test
    void getMessages_shouldReturnForwardCatchUp_whenAfterProvided() {
        MessagePage page = new MessagePage(List.of(), null, false);
        when(messageService.getMessagesSince(room, 10L, 50)).thenReturn(page);

        ResponseEntity<MessagePage> response = controller.getMessages(roomId, null, 10L, 50, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(page);
        verify(messageService, never()).getMessageHistory(room, null, 50);
    }

    @Test
    void getMessages_shouldClampSizeToUpperBound_whenSizeExceedsMax() {
        MessagePage page = new MessagePage(List.of(), null, false);
        when(messageService.getMessageHistory(room, null, 100)).thenReturn(page);

        ResponseEntity<MessagePage> response = controller.getMessages(roomId, null, null, 500, principal);

        assertThat(response.getBody()).isSameAs(page);
    }

    // --- sendMessage ---

    @Test
    void sendMessage_shouldReturnCreatedAndBroadcast_whenNoReplyTo() {
        ChatMessageRequest request = new ChatMessageRequest(roomId, "Hello", null);
        Message created = Message.builder()
                .id(messageId)
                .room(room)
                .sender(user)
                .content("Hello")
                .watermark(1L)
                .build();
        when(messageService.sendMessage(room, user, "Hello", null)).thenReturn(created);

        ResponseEntity<ChatMessageResponse> response = controller.sendMessage(roomId, request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ChatMessageResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(messageId);
        assertThat(body.senderId()).isEqualTo(user.getId());
        assertThat(body.senderUsername()).isEqualTo("testuser");
        assertThat(body.senderDisplayName()).isEqualTo("Test User");
        assertThat(body.replyToId()).isNull();
        assertThat(body.replyToSenderUsername()).isNull();
        assertThat(body.replyToContentSnippet()).isNull();
        verify(messageBroadcastService).broadcastNewMessage(room, user, body);
    }

    @Test
    void sendMessage_shouldResolveReplyToAndBuildSnippet_whenReplyToIdProvided() {
        UUID replyToId = UUID.randomUUID();
        User replySender = User.builder()
                .id(UUID.randomUUID())
                .username("replier")
                .displayName("Replier")
                .build();
        Message replyTo = Message.builder()
                .id(replyToId)
                .room(room)
                .sender(replySender)
                .content("short reply")
                .build();
        ChatMessageRequest request = new ChatMessageRequest(roomId, "Hi", replyToId);
        Message created = Message.builder()
                .id(messageId)
                .room(room)
                .sender(user)
                .content("Hi")
                .replyTo(replyTo)
                .build();
        when(messageRepository.findByIdWithSender(replyToId)).thenReturn(Optional.of(replyTo));
        when(messageService.sendMessage(room, user, "Hi", replyTo)).thenReturn(created);

        ResponseEntity<ChatMessageResponse> response = controller.sendMessage(roomId, request, principal);

        ChatMessageResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.replyToId()).isEqualTo(replyToId);
        assertThat(body.replyToSenderUsername()).isEqualTo("replier");
        assertThat(body.replyToContentSnippet()).isEqualTo("short reply");
    }

    @Test
    void sendMessage_shouldThrowNotFound_whenReplyToMessageMissing() {
        UUID replyToId = UUID.randomUUID();
        ChatMessageRequest request = new ChatMessageRequest(roomId, "Hi", replyToId);
        when(messageRepository.findByIdWithSender(replyToId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.sendMessage(roomId, request, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(replyToId.toString());

        verify(messageService, never()).sendMessage(room, user, "Hi", null);
    }

    // --- editMessage ---

    @Test
    void editMessage_shouldReturnResponseAndBroadcastEdit_whenEditSucceeds() {
        Message edited = Message.builder()
                .id(messageId)
                .room(room)
                .sender(user)
                .content("edited content")
                .edited(true)
                .build();
        EditMessageRequest request = new EditMessageRequest("edited content");
        when(messageService.editMessage(messageId, user, "edited content")).thenReturn(edited);

        ResponseEntity<ChatMessageResponse> response = controller.editMessage(roomId, messageId, request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatMessageResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.content()).isEqualTo("edited content");
        assertThat(body.edited()).isTrue();
        verify(messageBroadcastService).broadcastEdit(room, messageId, "edited content");
    }

    @Test
    void editMessage_shouldMarkDeletedUserAndTruncateSnippet_whenSenderNullAndReplyLong() {
        String longContent = "x".repeat(150);
        Message replyTo = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(null)
                .content(longContent)
                .build();
        Message edited = Message.builder()
                .id(messageId)
                .room(room)
                .sender(null)
                .content("still here")
                .replyTo(replyTo)
                .build();
        EditMessageRequest request = new EditMessageRequest("still here");
        when(messageService.editMessage(messageId, user, "still here")).thenReturn(edited);

        ResponseEntity<ChatMessageResponse> response = controller.editMessage(roomId, messageId, request, principal);

        ChatMessageResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.senderId()).isNull();
        assertThat(body.senderUsername()).isEqualTo("Deleted user");
        assertThat(body.senderDisplayName()).isNull();
        assertThat(body.replyToSenderUsername()).isEqualTo("Deleted user");
        assertThat(body.replyToContentSnippet()).hasSize(100);
        assertThat(body.replyToContentSnippet()).isEqualTo(longContent.substring(0, 100));
    }

    // --- deleteMessage ---

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
        verify(messageBroadcastService).recomputeUnread(room);
    }

    // --- resolveUser ---

    @Test
    void deleteMessage_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.deleteMessage(roomId, messageId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(messageService, never()).deleteMessage(messageId, user, room);
    }
}
