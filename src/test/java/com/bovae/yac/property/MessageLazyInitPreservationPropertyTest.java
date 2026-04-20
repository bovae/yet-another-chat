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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preservation property tests for Message lazy initialization fix.
 *
 * These tests capture baseline behavior on UNFIXED code that must remain unchanged
 * after the fix is applied. All tests here exercise code paths where the bug condition
 * does NOT hold (no replyTo proxy access outside transaction).
 *
 * EXPECTED: All tests PASS on both unfixed and fixed code.
 *
 * Validates: Requirements 3.1, 3.3, 3.4, 3.5
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MessageLazyInitPreservationPropertyTest {

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
     * Property 2a — Edit message without replyTo returns null reply-to fields.
     *
     * Validates: Requirements 3.1
     *
     * Non-bug condition: NOT isBugCondition(X) where X.operation = "editMessage" AND X.message.replyTo = null
     *
     * For all valid content strings, PUT /api/rooms/{roomId}/messages/{id} on a message
     * with no replyTo SHALL return HTTP 200 with reply_to_id = null,
     * reply_to_sender_username = null, reply_to_content_snippet = null,
     * content matching the new content, and edited = true.
     */
    @Property(tries = 5)
    void editMessageWithoutReplyToReturnsNullReplyToFields(
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String originalContent,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String editedContent
    ) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + suffix + "@test.com";
        String username = "user" + suffix;

        UserDto userDto = userService.register(email, username, "password123");
        User owner = userRepository.findById(userDto.id()).orElseThrow();

        RoomDto roomDto = roomService.createRoom(
                "room-" + suffix, "test room", RoomVisibility.PUBLIC, owner);
        Room room = roomRepository.findById(roomDto.id()).orElseThrow();

        // Send message WITHOUT replyTo
        Message message = messageService.sendMessage(room, owner, originalContent, null);

        // PUT edit the message — no replyTo, so no lazy proxy issue
        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), message.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"%s\"}".formatted(editedContent))
                        .with(user(email).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply_to_id").doesNotExist())
                .andExpect(jsonPath("$.reply_to_sender_username").doesNotExist())
                .andExpect(jsonPath("$.reply_to_content_snippet").doesNotExist())
                .andExpect(jsonPath("$.content").value(editedContent))
                .andExpect(jsonPath("$.edited").value(true));
    }

    /**
     * Property 2b — Send message via REST POST without replyToId returns null reply-to fields.
     *
     * Validates: Requirements 3.4
     *
     * For all valid content strings, POST /api/rooms/{roomId}/messages with no reply_to_id
     * SHALL return HTTP 201 with non-null sender_username and null reply_to_id,
     * reply_to_sender_username, reply_to_content_snippet.
     */
    @Property(tries = 5)
    void sendMessageWithoutReplyToReturnsNullReplyToFields(
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String content
    ) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + suffix + "@test.com";
        String username = "user" + suffix;

        UserDto userDto = userService.register(email, username, "password123");
        User owner = userRepository.findById(userDto.id()).orElseThrow();

        RoomDto roomDto = roomService.createRoom(
                "room-" + suffix, "test room", RoomVisibility.PUBLIC, owner);
        Room room = roomRepository.findById(roomDto.id()).orElseThrow();

        // POST message without reply_to_id
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"room_id\": \"%s\", \"content\": \"%s\"}".formatted(room.getId(), content))
                        .with(user(email).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sender_username").isNotEmpty())
                .andExpect(jsonPath("$.reply_to_id").doesNotExist())
                .andExpect(jsonPath("$.reply_to_sender_username").doesNotExist())
                .andExpect(jsonPath("$.reply_to_content_snippet").doesNotExist());
    }

    /**
     * Property 2c — Send message via REST POST with replyToId returns correct reply-to data.
     *
     * Validates: Requirements 3.4
     *
     * Create an original message, then send a reply via POST /api/rooms/{roomId}/messages
     * with reply_to_id set. Assert HTTP 201 with non-null reply_to_sender_username
     * and non-null reply_to_content_snippet.
     *
     * This path already works because MessageApiController.sendMessage uses findByIdWithSender
     * for the replyTo lookup and passes the entity directly to sendMessage.
     */
    @Property(tries = 5)
    void sendMessageWithReplyToReturnsPopulatedReplyToFields(
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String originalContent,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String replyContent
    ) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + suffix + "@test.com";
        String username = "user" + suffix;

        UserDto userDto = userService.register(email, username, "password123");
        User owner = userRepository.findById(userDto.id()).orElseThrow();

        RoomDto roomDto = roomService.createRoom(
                "room-" + suffix, "test room", RoomVisibility.PUBLIC, owner);
        Room room = roomRepository.findById(roomDto.id()).orElseThrow();

        // Send original message via service
        Message originalMessage = messageService.sendMessage(room, owner, originalContent, null);

        // POST reply message with reply_to_id
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"room_id\": \"%s\", \"content\": \"%s\", \"reply_to_id\": \"%s\"}"
                                .formatted(room.getId(), replyContent, originalMessage.getId()))
                        .with(user(email).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reply_to_sender_username").isNotEmpty())
                .andExpect(jsonPath("$.reply_to_content_snippet").isNotEmpty());
    }

    /**
     * Property 2d — Message history returns fully populated reply-to data.
     *
     * Validates: Requirements 3.3
     *
     * Create a message with a reply, then fetch history via GET /api/rooms/{roomId}/messages.
     * Assert response contains the reply message with non-null reply_to_sender_username
     * for messages that have replyTo.
     *
     * This path uses findByRoomAndWatermarkGreaterThanWithFetches which already joins
     * replyTo and replyTo.sender correctly.
     */
    @Property(tries = 5)
    void messageHistoryReturnsPopulatedReplyToData(
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String originalContent,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String replyContent
    ) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + suffix + "@test.com";
        String username = "user" + suffix;

        UserDto userDto = userService.register(email, username, "password123");
        User owner = userRepository.findById(userDto.id()).orElseThrow();

        RoomDto roomDto = roomService.createRoom(
                "room-" + suffix, "test room", RoomVisibility.PUBLIC, owner);
        Room room = roomRepository.findById(roomDto.id()).orElseThrow();

        // Send original message and a reply
        Message originalMessage = messageService.sendMessage(room, owner, originalContent, null);
        messageService.sendMessage(room, owner, replyContent, originalMessage);

        // GET message history
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(email).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages.length()").value(2))
                // The reply message (second in watermark order) should have populated reply-to fields
                .andExpect(jsonPath("$.messages[1].reply_to_sender_username").isNotEmpty())
                .andExpect(jsonPath("$.messages[1].reply_to_content_snippet").isNotEmpty());
    }

    /**
     * Property 2e — Delete message succeeds regardless of replyTo.
     *
     * Validates: Requirements 3.5
     *
     * Create a message with a replyTo, then DELETE /api/rooms/{roomId}/messages/{id}.
     * Assert HTTP 204.
     */
    @Property(tries = 5)
    void deleteMessageWithReplyToSucceeds(
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String originalContent,
            @ForAll @AlphaChars @StringLength(min = 1, max = 100) String replyContent
    ) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "user-" + suffix + "@test.com";
        String username = "user" + suffix;

        UserDto userDto = userService.register(email, username, "password123");
        User owner = userRepository.findById(userDto.id()).orElseThrow();

        RoomDto roomDto = roomService.createRoom(
                "room-" + suffix, "test room", RoomVisibility.PUBLIC, owner);
        Room room = roomRepository.findById(roomDto.id()).orElseThrow();

        // Send original message and a reply
        Message originalMessage = messageService.sendMessage(room, owner, originalContent, null);
        Message replyMessage = messageService.sendMessage(room, owner, replyContent, originalMessage);

        // DELETE the reply message (which has a replyTo)
        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), replyMessage.getId())
                        .with(user(email).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }
}
