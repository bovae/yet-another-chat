package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug Condition exploration property test for Message lazy initialization fix.
 *
 * This test surfaces the LazyInitializationException that occurs when:
 * 1a) Editing a message with a replyTo reference via REST PUT — toResponse() accesses
 *     replyTo.getSender().getUsername() outside the transaction boundary
 * 1b) Fetching a reply message via repository.findById() outside a transaction —
 *     simulates the WebSocket ChatMessageHandler.sendMessage path
 *
 * EXPECTED: These tests FAIL on unfixed code, confirming the bug exists.
 *
 * Validates: Requirements 1.1, 1.2, 1.3
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MessageLazyInitBugConditionPropertyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomBanRepository roomBanRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private UnreadMarkerRepository unreadMarkerRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterTry
    void cleanup() {
        attachmentRepository.deleteAll();
        unreadMarkerRepository.deleteAll();
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        // Nullify replyTo references before deleting messages
        for (Message msg : messageRepository.findAll()) {
            if (msg.getReplyTo() != null) {
                msg.setReplyTo(null);
                messageRepository.save(msg);
            }
        }
        messageRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * Property 1a — REST edit with replyTo.
     *
     * Validates: Requirements 1.1, 1.3
     *
     * Bug condition: isBugCondition(X) = X.operation = "editMessage" AND X.message.replyTo != null
     *
     * For any valid content string, editing a message that has a replyTo reference via
     * PUT /api/rooms/{roomId}/messages/{replyMessageId} SHALL return HTTP 200 with
     * non-null reply_to_sender_username and reply_to_content_snippet.
     *
     * On UNFIXED code: findByIdWithSender only fetches sender, not replyTo/replyTo.sender,
     * so toResponse() triggers LazyInitializationException → HTTP 500.
     */
    @Property(tries = 5)
    void editMessageWithReplyToReturns200WithPopulatedReplyFields(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String originalContent,
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String replyContent,
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String editedContent
    ) throws Exception {
        // Set up: register user, create room, send original message, send reply
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + suffix + "@test.com";
        String username = "user" + suffix;

        UserDto userDto = userService.register(email, username, "password123");
        User owner = userRepository.findById(userDto.id()).orElseThrow();

        RoomDto roomDto = roomService.createRoom(
                "room-" + suffix, "test room", RoomVisibility.PUBLIC, owner);
        Room room = roomRepository.findById(roomDto.id()).orElseThrow();

        // Send original message
        Message originalMessage = messageService.sendMessage(room, owner, originalContent, null);

        // Send reply message with replyTo set
        Message replyMessage = messageService.sendMessage(room, owner, replyContent, originalMessage);

        // PUT edit the reply message — this triggers the bug
        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), replyMessage.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"%s\"}".formatted(editedContent))
                        .with(user(email).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply_to_sender_username").isNotEmpty())
                .andExpect(jsonPath("$.reply_to_content_snippet").isNotEmpty());
    }

    /**
     * Property 1b — Repository-level proxy access simulating WebSocket path.
     *
     * Validates: Requirements 1.2
     *
     * Bug condition: isBugCondition(X) = X.operation = "sendMessageWebSocket" AND X.replyToId != null
     *
     * For any valid content, fetching a reply message via messageRepository.findByIdWithSenderAndReplyTo()
     * outside a @Transactional boundary and accessing message.getReplyTo().getSender().getUsername()
     * SHALL NOT throw LazyInitializationException and SHALL return a non-null username.
     *
     * This tests the fixed query path that ChatMessageHandler.sendMessage now uses:
     * findByIdWithSenderAndReplyTo eagerly fetches sender, replyTo, and replyTo.sender.
     *
     * Note: MockMvc cannot test STOMP directly, so this tests the same root cause
     * at the repository level — simulating ChatMessageHandler.sendMessage's fetch pattern.
     */
    @Property(tries = 5)
    void findByIdOutsideTransactionAllowsReplyToSenderAccess(
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String originalContent,
            @ForAll @AlphaChars @StringLength(min = 1, max = 50) String replyContent
    ) {
        // Set up: register user, create room, send original message, send reply
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + suffix + "@test.com";
        String username = "user" + suffix;

        UserDto userDto = userService.register(email, username, "password123");
        User owner = userRepository.findById(userDto.id()).orElseThrow();

        RoomDto roomDto = roomService.createRoom(
                "room-" + suffix, "test room", RoomVisibility.PUBLIC, owner);
        Room room = roomRepository.findById(roomDto.id()).orElseThrow();

        // Send original message
        Message originalMessage = messageService.sendMessage(room, owner, originalContent, null);

        // Send reply message with replyTo set
        Message replyMessage = messageService.sendMessage(room, owner, replyContent, originalMessage);

        // Fetch via findByIdWithSenderAndReplyTo OUTSIDE @Transactional — matches the fixed
        // ChatMessageHandler.sendMessage path which now uses this query instead of findById
        // The test class is NOT @Transactional, so this access is outside any Hibernate session
        Message fetched = messageRepository.findByIdWithSenderAndReplyTo(replyMessage.getId()).orElseThrow();

        // Access the lazy proxy chain — this is what ChatMessageHandler does
        // The fixed query eagerly fetches replyTo and replyTo.sender, so no LazyInitializationException
        String senderUsername = fetched.getReplyTo().getSender().getUsername();

        assertThat(senderUsername)
                .as("replyTo.sender.username must be non-null when accessed outside transaction")
                .isNotNull();
    }
}
