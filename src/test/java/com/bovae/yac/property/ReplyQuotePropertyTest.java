package com.bovae.yac.property;

import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.UserBanService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for reply quote DTO enrichment.
 *
 * Property 9: Reply quote DTO enrichment
 *
 * For any message with a non-null replyTo reference, the ChatMessageResponse SHALL have
 * replyToSenderUsername equal to the replied-to message's sender username, and
 * replyToContentSnippet equal to the first 100 characters of the replied-to message's content.
 * For any message with a null replyTo, both fields SHALL be null.
 *
 * Validates: Requirements 14.1, 14.2, 14.3
 */
class ReplyQuotePropertyTest {

    private MessageRepository messageRepository;
    private RoomRepository roomRepository;
    private RoomMemberService roomMemberService;
    private UserBanService userBanService;
    private RoomMemberRepository roomMemberRepository;
    private UserRepository userRepository;
    private AttachmentRepository attachmentRepository;
    private MessageService messageService;

    @BeforeTry
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        roomRepository = mock(RoomRepository.class);
        roomMemberService = mock(RoomMemberService.class);
        userBanService = mock(UserBanService.class);
        roomMemberRepository = mock(RoomMemberRepository.class);
        userRepository = mock(UserRepository.class);
        attachmentRepository = mock(AttachmentRepository.class);
        messageService = new MessageService(
                messageRepository, roomRepository, roomMemberService,
                userBanService, roomMemberRepository, userRepository,
                attachmentRepository
        );
    }

    @Provide
    Arbitrary<String> messageContent() {
        // Generate content from 1 to 3072 characters (within the max byte limit for ASCII)
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(3072);
    }

    @Provide
    Arbitrary<String> senderUsernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    private User buildUser(String username) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(username + "@example.com")
                .username(username)
                .passwordHash("hashed")
                .build();
    }

    private Room buildRoom() {
        return Room.builder()
                .id(UUID.randomUUID())
                .name("room-" + UUID.randomUUID().toString().substring(0, 8))
                .visibility(RoomVisibility.PUBLIC)
                .nextWatermark(10L)
                .build();
    }

    private Message buildMessage(Room room, User sender, String content, Long watermark, Message replyTo) {
        return Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content(content)
                .edited(false)
                .watermark(watermark)
                .replyTo(replyTo)
                .build();
    }

    /**
     * Property 9: For any message with a non-null replyTo reference, the ChatMessageResponse
     * SHALL have replyToSenderUsername equal to the replied-to message's sender username,
     * and replyToContentSnippet equal to the first 100 characters of the replied-to message's content.
     *
     * Validates: Requirements 14.1, 14.2, 14.3
     */
    @Property(tries = 20)
    void nonNullReplyTo_shallPopulateReplyQuoteFields(
            @ForAll("messageContent") String originalContent,
            @ForAll("senderUsernames") String senderUsername,
            @ForAll("senderUsernames") String replierUsername
    ) {
        Room room = buildRoom();
        User originalSender = buildUser(senderUsername);
        User replier = buildUser(replierUsername);

        Message originalMessage = buildMessage(room, originalSender, originalContent, 1L, null);
        Message replyMessage = buildMessage(room, replier, "reply text", 2L, originalMessage);

        // Stub repository to return the reply message
        when(messageRepository.findByRoomAndWatermarkGreaterThanOrderByWatermarkAsc(
                eq(room), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(replyMessage));
        when(attachmentRepository.findByMessageIdIn(any()))
                .thenReturn(Collections.emptyList());

        MessagePage page = messageService.getMessageHistory(room, null, 50);

        assertThat(page.messages()).hasSize(1);
        ChatMessageResponse response = page.messages().getFirst();

        // replyToSenderUsername SHALL equal the replied-to message's sender username
        assertThat(response.replyToSenderUsername())
                .as("replyToSenderUsername should equal the original sender's username")
                .isEqualTo(originalSender.getUsername());

        // replyToContentSnippet SHALL equal the first 100 characters of the replied-to content
        String expectedSnippet = originalContent.length() > 100
                ? originalContent.substring(0, 100)
                : originalContent;
        assertThat(response.replyToContentSnippet())
                .as("replyToContentSnippet should be the first 100 chars of original content")
                .isEqualTo(expectedSnippet);

        // replyToId should be set
        assertThat(response.replyToId())
                .isEqualTo(originalMessage.getId());
    }

    /**
     * Property 9: For any message with a null replyTo, both replyToSenderUsername and
     * replyToContentSnippet SHALL be null.
     *
     * Validates: Requirements 14.1, 14.2, 14.3
     */
    @Property(tries = 20)
    void nullReplyTo_shallHaveNullReplyQuoteFields(
            @ForAll("messageContent") String content,
            @ForAll("senderUsernames") String username
    ) {
        Room room = buildRoom();
        User sender = buildUser(username);

        Message message = buildMessage(room, sender, content, 1L, null);

        when(messageRepository.findByRoomAndWatermarkGreaterThanOrderByWatermarkAsc(
                eq(room), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(message));
        when(attachmentRepository.findByMessageIdIn(any()))
                .thenReturn(Collections.emptyList());

        MessagePage page = messageService.getMessageHistory(room, null, 50);

        assertThat(page.messages()).hasSize(1);
        ChatMessageResponse response = page.messages().getFirst();

        assertThat(response.replyToSenderUsername())
                .as("replyToSenderUsername should be null when replyTo is null")
                .isNull();
        assertThat(response.replyToContentSnippet())
                .as("replyToContentSnippet should be null when replyTo is null")
                .isNull();
        assertThat(response.replyToId())
                .as("replyToId should be null when replyTo is null")
                .isNull();
    }

    /**
     * Property 9: Content snippet truncation — for any replied-to message content longer
     * than 100 characters, the snippet SHALL be exactly 100 characters.
     * For content <= 100 characters, the snippet SHALL equal the full content.
     *
     * Validates: Requirements 14.1, 14.2, 14.3
     */
    @Property(tries = 20)
    void replyToContentSnippet_shallNeverExceed100Characters(
            @ForAll("messageContent") String originalContent,
            @ForAll("senderUsernames") String senderUsername
    ) {
        Room room = buildRoom();
        User sender = buildUser(senderUsername);
        User replier = buildUser("replier");

        Message originalMessage = buildMessage(room, sender, originalContent, 1L, null);
        Message replyMessage = buildMessage(room, replier, "reply", 2L, originalMessage);

        when(messageRepository.findByRoomAndWatermarkGreaterThanOrderByWatermarkAsc(
                eq(room), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(replyMessage));
        when(attachmentRepository.findByMessageIdIn(any()))
                .thenReturn(Collections.emptyList());

        MessagePage page = messageService.getMessageHistory(room, null, 50);
        ChatMessageResponse response = page.messages().getFirst();

        assertThat(response.replyToContentSnippet())
                .as("Snippet should never exceed 100 characters")
                .hasSizeLessThanOrEqualTo(100);

        if (originalContent.length() <= 100) {
            assertThat(response.replyToContentSnippet())
                    .as("Snippet should equal full content when <= 100 chars")
                    .isEqualTo(originalContent);
        } else {
            assertThat(response.replyToContentSnippet())
                    .as("Snippet should be exactly 100 chars when content is longer")
                    .hasSize(100)
                    .isEqualTo(originalContent.substring(0, 100));
        }
    }
}
