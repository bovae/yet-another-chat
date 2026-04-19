package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for MessageService: message content round-trip,
 * message edit invariant, and message deletion by author.
 *
 * Validates: Requirements 13.1, 13.2, 13.3, 13.4, 13.6, 13.8
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class MessagePropertyTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomBanRepository roomBanRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @AfterTry
    void cleanup() {
        // Nullify self-referencing reply FKs before deleting messages
        messageRepository.findAll().forEach(m -> {
            if (m.getReplyTo() != null) {
                m.setReplyTo(null);
                messageRepository.save(m);
            }
        });
        messageRepository.deleteAll();
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Provide
    Arbitrary<String> validEmails() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(12)
                .map(local -> local.toLowerCase() + "@example.com");
    }

    @Provide
    Arbitrary<String> validUsernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> validPasswords() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(8)
                .ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(30)
                .map(String::toLowerCase);
    }

    /**
     * Generates valid message content: plain text, multiline, and emoji,
     * constrained to at most 3072 UTF-8 bytes.
     * Excludes null bytes which PostgreSQL rejects in text columns.
     */
    @Provide
    Arbitrary<String> validMessageContents() {
        Arbitrary<String> plainText = Arbitraries.strings()
                .withCharRange(' ', '~')
                .ofMinLength(1)
                .ofMaxLength(200);

        Arbitrary<String> multiline = Arbitraries.strings()
                .withCharRange(' ', '~')
                .ofMinLength(1)
                .ofMaxLength(50)
                .list()
                .ofMinSize(2)
                .ofMaxSize(5)
                .map(lines -> String.join("\n", lines));

        Arbitrary<String> withEmoji = plainText.map(s -> s + " \uD83D\uDE00\uD83D\uDC4D\u2764");

        return Arbitraries.oneOf(plainText, multiline, withEmoji);
    }

    /**
     * Generates message content that exceeds 3072 UTF-8 bytes.
     * Uses printable ASCII (no null bytes) to avoid PostgreSQL encoding errors.
     */
    @Provide
    Arbitrary<String> oversizedMessageContents() {
        return Arbitraries.strings()
                .withCharRange(' ', '~')
                .ofMinLength(3073)
                .ofMaxLength(4000);
    }

    // Feature: online-chat-server, Property 17: Message content round-trip
    /**
     * Validates: Requirements 13.1, 13.2, 13.3, 13.8
     *
     * For any valid message content (plain text, multiline, emoji, up to 3072 UTF-8 bytes)
     * sent to a Room, the persisted Message SHALL contain the exact same content.
     * Messages with a replyTo reference SHALL store the correct FK.
     * Messages exceeding 3072 bytes SHALL be rejected.
     */
    @Property(tries = 50)
    void messageContentRoundTrip(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll("validMessageContents") String content
    ) {
        // Setup: register user, create room, join room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userDto = userService.register(email + suffix, username + suffix, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Message test room", RoomVisibility.PUBLIC, user).id());

        // Verify content is within the valid byte limit
        assertThat(content.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(3072);

        // Send a message — persisted content SHALL be identical
        Message sent = messageService.sendMessage(room, user, content, null);
        Message persisted = messageRepository.findById(sent.getId()).orElseThrow();
        assertThat(persisted.getContent()).isEqualTo(content);

        // Send a reply — replyTo FK SHALL reference the original message
        Message reply = messageService.sendMessage(room, user, "Reply to: " + content, sent);
        Message persistedReply = messageRepository.findById(reply.getId()).orElseThrow();
        assertThat(persistedReply.getReplyTo()).isNotNull();
        assertThat(persistedReply.getReplyTo().getId()).isEqualTo(sent.getId());
    }

    // Feature: online-chat-server, Property 17: Message content round-trip (oversized rejection)
    @Property(tries = 50)
    void oversizedMessageShallBeRejected(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll("oversizedMessageContents") String oversizedContent
    ) {
        // Setup: register user, create room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userDto = userService.register(email + suffix, username + suffix, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Oversized test room", RoomVisibility.PUBLIC, user).id());

        // Verify content exceeds the byte limit
        assertThat(oversizedContent.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(3072);

        // Sending oversized content SHALL be rejected
        assertThatThrownBy(() -> messageService.sendMessage(room, user, oversizedContent, null))
                .isInstanceOf(IllegalArgumentException.class);

        // No message should have been persisted
        assertThat(messageRepository.findByRoom(room)).isEmpty();
    }

    // Feature: online-chat-server, Property 18: Message edit invariant
    /**
     * Validates: Requirements 13.4
     *
     * For any Message edited by its author, the content SHALL be updated to the new value
     * and the edited flag SHALL be set to true.
     */
    @Property(tries = 50)
    void messageEditInvariant(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll("validMessageContents") String originalContent,
            @ForAll("validMessageContents") String newContent
    ) {
        // Setup: register user, create room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userDto = userService.register(email + suffix, username + suffix, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Edit test room", RoomVisibility.PUBLIC, user).id());

        // Send original message
        Message original = messageService.sendMessage(room, user, originalContent, null);
        assertThat(original.isEdited()).isFalse();

        // Edit the message
        Message edited = messageService.editMessage(original.getId(), user, newContent);

        // Content SHALL be updated to the new value
        assertThat(edited.getContent()).isEqualTo(newContent);

        // Edited flag SHALL be set to true
        assertThat(edited.isEdited()).isTrue();

        // Verify persistence
        Message persisted = messageRepository.findById(original.getId()).orElseThrow();
        assertThat(persisted.getContent()).isEqualTo(newContent);
        assertThat(persisted.isEdited()).isTrue();
    }

    // Feature: online-chat-server, Property 19: Message deletion by author
    /**
     * Validates: Requirements 13.6
     *
     * For any Message, the original author SHALL be able to delete it permanently,
     * and the Message SHALL no longer be retrievable.
     */
    @Property(tries = 50)
    void messageDeletionByAuthor(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll("validMessageContents") String content
    ) {
        // Setup: register user, create room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userDto = userService.register(email + suffix, username + suffix, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Delete test room", RoomVisibility.PUBLIC, user).id());

        // Send a message
        Message message = messageService.sendMessage(room, user, content, null);
        UUID messageId = message.getId();

        // Verify message exists
        assertThat(messageRepository.findById(messageId)).isPresent();

        // Author deletes the message
        messageService.deleteMessage(messageId, user, room);

        // Message SHALL no longer be retrievable
        assertThat(messageRepository.findById(messageId)).isEmpty();
    }
}
