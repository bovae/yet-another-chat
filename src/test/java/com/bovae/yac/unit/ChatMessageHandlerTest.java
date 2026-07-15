package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.ErrorResponse;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.ws.ChatMessageHandler;
import jakarta.validation.ConstraintViolationException;
import java.security.Principal;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for {@link ChatMessageHandler}.
 *
 * <p>Covers reply-metadata assembly (snippet truncation, missing sender) on {@code /chat.send} and
 * the STOMP exception-to-{@link ErrorResponse} mapping that never leaks internals (R1-37).
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageHandlerTest {

    private static final String SENDER_EMAIL = "sender@test.com";

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private MessageService messageService;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private ChatMessageHandler chatMessageHandler;

    private Principal principal;
    private User sender;
    private Room room;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        principal = () -> SENDER_EMAIL;
        sender = User.builder()
                .id(UUID.randomUUID())
                .email(SENDER_EMAIL)
                .username("sender")
                .displayName("The Sender")
                .passwordHash("$2a$10$hash")
                .build();
        roomId = UUID.randomUUID();
        room = Room.builder()
                .id(roomId)
                .name("room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(sender)
                .nextWatermark(1L)
                .build();
    }

    // --- sendMessage ---

    @Test
    void sendMessage_broadcastsResponseWithoutReplyFields_whenNoReply() {
        stubResolveSenderAndRoom();
        Message saved = savedMessage("hello");
        when(messageService.sendMessage(eq(room), eq(sender), eq("hello"), any()))
                .thenReturn(saved);

        chatMessageHandler.sendMessage(new ChatMessageRequest(roomId, "hello", null), principal);

        ChatMessageResponse response = captureBroadcast();
        assertThat(response.id()).isEqualTo(saved.getId());
        assertThat(response.roomId()).isEqualTo(roomId);
        assertThat(response.senderId()).isEqualTo(sender.getId());
        assertThat(response.senderUsername()).isEqualTo("sender");
        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.replyToId()).isNull();
        assertThat(response.replyToSenderUsername()).isNull();
        assertThat(response.replyToContentSnippet()).isNull();
    }

    @Test
    void sendMessage_broadcastsReplyMetadata_whenShortReplyProvided() {
        UUID replyToId = UUID.randomUUID();
        User replier = User.builder()
                .id(UUID.randomUUID())
                .email("replier@test.com")
                .username("replier")
                .passwordHash("$2a$10$hash")
                .build();
        Message replyTo = Message.builder()
                .id(replyToId)
                .sender(replier)
                .content("original text")
                .build();
        stubResolveSenderAndRoom();
        when(messageRepository.findByIdWithSenderAndReplyTo(replyToId)).thenReturn(Optional.of(replyTo));
        when(messageService.sendMessage(eq(room), eq(sender), eq("hello"), any()))
                .thenReturn(savedMessage("hello"));

        chatMessageHandler.sendMessage(new ChatMessageRequest(roomId, "hello", replyToId), principal);

        ChatMessageResponse response = captureBroadcast();
        assertThat(response.replyToId()).isEqualTo(replyToId);
        assertThat(response.replyToSenderUsername()).isEqualTo("replier");
        assertThat(response.replyToContentSnippet()).isEqualTo("original text");
    }

    @Test
    void sendMessage_truncatesReplySnippetToHundredChars_whenReplyContentIsLong() {
        UUID replyToId = UUID.randomUUID();
        String longContent = "a".repeat(150);
        Message replyTo = Message.builder()
                .id(replyToId)
                .sender(sender)
                .content(longContent)
                .build();
        stubResolveSenderAndRoom();
        when(messageRepository.findByIdWithSenderAndReplyTo(replyToId)).thenReturn(Optional.of(replyTo));
        when(messageService.sendMessage(eq(room), eq(sender), eq("hello"), any()))
                .thenReturn(savedMessage("hello"));

        chatMessageHandler.sendMessage(new ChatMessageRequest(roomId, "hello", replyToId), principal);

        ChatMessageResponse response = captureBroadcast();
        assertThat(response.replyToContentSnippet()).hasSize(100);
        assertThat(response.replyToContentSnippet()).isEqualTo(longContent.substring(0, 100));
    }

    @Test
    void sendMessage_omitsReplySenderUsername_whenReplyHasNoSender() {
        UUID replyToId = UUID.randomUUID();
        Message replyTo = Message.builder()
                .id(replyToId)
                .sender(null)
                .content("orphaned reply")
                .build();
        stubResolveSenderAndRoom();
        when(messageRepository.findByIdWithSenderAndReplyTo(replyToId)).thenReturn(Optional.of(replyTo));
        when(messageService.sendMessage(eq(room), eq(sender), eq("hello"), any()))
                .thenReturn(savedMessage("hello"));

        chatMessageHandler.sendMessage(new ChatMessageRequest(roomId, "hello", replyToId), principal);

        ChatMessageResponse response = captureBroadcast();
        assertThat(response.replyToSenderUsername()).isNull();
        assertThat(response.replyToContentSnippet()).isEqualTo("orphaned reply");
    }

    @Test
    void sendMessage_throwsResourceNotFound_whenReplyMessageMissing() {
        UUID replyToId = UUID.randomUUID();
        stubResolveSenderAndRoom();
        when(messageRepository.findByIdWithSenderAndReplyTo(replyToId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        chatMessageHandler.sendMessage(new ChatMessageRequest(roomId, "hello", replyToId), principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Reply-to message not found");

        verify(messageBroadcastService, never()).broadcastNewMessage(any(), any(), any());
    }

    @Test
    void sendMessage_throwsResourceNotFound_whenSenderUnknown() {
        when(userRepository.findByEmail(SENDER_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> chatMessageHandler.sendMessage(new ChatMessageRequest(roomId, "hello", null), principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");

        verify(messageBroadcastService, never()).broadcastNewMessage(any(), any(), any());
    }

    // --- handleException ---

    static Stream<Arguments> exceptionMappings() {
        return Stream.of(
                Arguments.of(new ForbiddenException("not allowed"), 403, "not allowed"),
                Arguments.of(new ResourceNotFoundException("gone"), 404, "gone"),
                Arguments.of(new IllegalArgumentException("bad arg"), 400, "bad arg"),
                Arguments.of(new ConstraintViolationException("invalid", Collections.emptySet()), 400, "invalid"),
                Arguments.of(new RuntimeException("stack trace"), 500, "An unexpected error occurred"));
    }

    @ParameterizedTest(name = "{0} -> status={1}, message={2}")
    @MethodSource("exceptionMappings")
    void handleException_sendsMappedErrorToUser_whenExceptionThrown(
            Exception ex, int expectedStatus, String expectedMessage) {
        chatMessageHandler.handleException(ex, principal);

        ArgumentCaptor<ErrorResponse> errorCaptor = ArgumentCaptor.forClass(ErrorResponse.class);
        verify(messagingTemplate).convertAndSendToUser(eq(SENDER_EMAIL), eq("/queue/errors"), errorCaptor.capture());
        ErrorResponse error = errorCaptor.getValue();
        assertThat(error.status()).isEqualTo(expectedStatus);
        assertThat(error.message()).isEqualTo(expectedMessage);
        assertThat(error.path()).isNull();
    }

    @Test
    void handleException_doesNotSend_whenPrincipalNull() {
        chatMessageHandler.handleException(new RuntimeException("boom"), null);

        verify(messagingTemplate, never()).convertAndSendToUser(any(String.class), any(String.class), any());
    }

    static Stream<Arguments> warnLogCases() {
        return Stream.of(
                Arguments.of(new ForbiddenException("nope"), 1), Arguments.of(new RuntimeException("boom"), 0));
    }

    @ParameterizedTest(name = "{0} -> warnEvents={1}")
    @MethodSource("warnLogCases")
    void handleException_logsWarnOnlyForNon500Status_whenExceptionThrown(Exception ex, int expectedWarnCount) {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatMessageHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            chatMessageHandler.handleException(ex, principal);

            long warnCount = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .count();
            assertThat(warnCount).isEqualTo(expectedWarnCount);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private void stubResolveSenderAndRoom() {
        when(userRepository.findByEmail(SENDER_EMAIL)).thenReturn(Optional.of(sender));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
    }

    private Message savedMessage(String content) {
        return Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content(content)
                .watermark(1L)
                .build();
    }

    private ChatMessageResponse captureBroadcast() {
        ArgumentCaptor<ChatMessageResponse> responseCaptor = ArgumentCaptor.forClass(ChatMessageResponse.class);
        verify(messageBroadcastService).broadcastNewMessage(eq(room), eq(sender), responseCaptor.capture());
        return responseCaptor.getValue();
    }
}
