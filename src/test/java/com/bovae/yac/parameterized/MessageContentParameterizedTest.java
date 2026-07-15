package com.bovae.yac.parameterized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parameterized boundary tests for message content round-trip.
 *
 * <p>Validates:
 * <ul>
 *   <li>CP 17 — Message content is persisted and retrieved exactly as sent,
 *       including plain text, multiline, emoji, and boundary UTF-8 byte sizes.
 *       Content exceeding 3072 UTF-8 bytes is rejected.</li>
 * </ul>
 *
 * <p>Requirements: 6.6
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class MessageContentParameterizedTest {

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private com.bovae.yac.repository.UserRepository userRepository;

    private User sender;
    private Room room;

    @BeforeEach
    void setUp() {
        UserDto senderDto = userService.register("sender@test.com", "sender", "password123");
        sender = userRepository.findById(senderDto.id()).orElseThrow();
        room = roomService.getRoomById(roomService
                .createRoom("msg-content-room", "test room", RoomVisibility.PUBLIC, sender)
                .id());
    }

    // ---- CP 17: Valid message content round-trip ----

    /**
     * Verifies that valid message content is persisted and retrieved exactly as sent,
     * covering plain text, multiline, emoji, and exactly 3072 UTF-8 bytes.
     *
     * <p>Validates: CP 17 — message content round-trip integrity.
     */
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("validMessageContents")
    void sendMessage_validContent_roundTrips(String content, String description) {
        Message message = messageService.sendMessage(room, sender, content, null);

        assertNotNull(message.getId());
        assertEquals(content, message.getContent(), "Content should round-trip exactly for: " + description);
    }

    static Stream<Arguments> validMessageContents() {
        return Stream.of(
                Arguments.of("Hello, world!", "plain text"),
                Arguments.of("Line one\nLine two\nLine three", "multiline text"),
                Arguments.of("Hello 👋🌍🎉🔥💯", "emoji content"),
                Arguments.of("a".repeat(3072), "exactly 3072 ASCII bytes"),
                Arguments.of(buildExactUtf8String(3072), "exactly 3072 UTF-8 bytes with multibyte chars"));
    }

    // ---- CP 17: Oversized message content rejection ----

    /**
     * Verifies that message content exceeding 3072 UTF-8 bytes is rejected
     * with an IllegalArgumentException.
     *
     * <p>Validates: CP 17 — oversized content is rejected.
     */
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("oversizedMessageContents")
    void sendMessage_oversizedContent_throwsException(String content, String description) {
        assertThrows(
                IllegalArgumentException.class,
                () -> messageService.sendMessage(room, sender, content, null),
                "Should reject oversized content for: " + description);
    }

    static Stream<Arguments> oversizedMessageContents() {
        return Stream.of(
                Arguments.of("a".repeat(3073), "3073 ASCII bytes (1 byte over)"),
                Arguments.of("a".repeat(4096), "4096 ASCII bytes (well over limit)"),
                // Each '€' is 3 UTF-8 bytes; 1025 * 3 = 3075 bytes > 3072
                Arguments.of("€".repeat(1025), "1025 euro signs (3075 UTF-8 bytes)"),
                // Each '𝄞' (musical symbol) is 4 UTF-8 bytes; 768 * 4 = 3072, add one more = 3076
                Arguments.of("𝄞".repeat(769), "769 4-byte chars (3076 UTF-8 bytes)"),
                Arguments.of("a".repeat(3070) + "€€", "mixed: 3070 ASCII + 2 euro signs = 3076 bytes"));
    }

    /**
     * Builds a string that is exactly {@code targetBytes} UTF-8 bytes long,
     * using a mix of ASCII and 2-byte characters (e.g., 'ñ' = 2 UTF-8 bytes).
     */
    private static String buildExactUtf8String(int targetBytes) {
        // 'ñ' is 2 UTF-8 bytes (U+00F1). Use a mix to hit exactly targetBytes.
        // Strategy: fill with 2-byte chars, then pad remaining byte with ASCII.
        StringBuilder sb = new StringBuilder();
        int remaining = targetBytes;
        while (remaining >= 2) {
            sb.append('ñ');
            remaining -= 2;
        }
        if (remaining == 1) {
            sb.append('a');
        }
        assert sb.toString().getBytes(StandardCharsets.UTF_8).length == targetBytes;
        return sb.toString();
    }
}
