package com.bovae.yac.parameterized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import java.util.ArrayList;
import java.util.List;
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
 * Parameterized boundary tests for watermark monotonicity and cursor-based pagination.
 *
 * <p>Validates:
 * <ul>
 *   <li>CP 22 — Watermarks are strictly monotonically increasing for sequences of messages</li>
 *   <li>CP 23 — Cursor-based pagination returns correct pages with has_more and next_cursor</li>
 * </ul>
 *
 * <p>Requirements: 6.7, 6.8
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PaginationParameterizedTest {

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
        UserDto senderDto = userService.register("pagination@test.com", "paginationuser", "password123");
        sender = userRepository.findById(senderDto.id()).orElseThrow();
        room = roomService.getRoomById(roomService
                .createRoom("pagination-room", "test room", RoomVisibility.PUBLIC, sender)
                .id());
    }

    // ---- CP 22: Watermark monotonicity ----

    /**
     * Verifies that watermarks assigned to messages are strictly monotonically increasing
     * for sequences of varying lengths.
     *
     * <p>Validates: CP 22 — each message's watermark is exactly one greater than the previous.
     */
    @ParameterizedTest(name = "[{index}] {0} messages")
    @MethodSource("messageSequenceSizes")
    void sendMessages_watermarksAreStrictlyMonotonic(int messageCount) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            messages.add(messageService.sendMessage(room, sender, "Message " + (i + 1), null));
        }

        for (int i = 0; i < messages.size(); i++) {
            assertEquals(
                    i + 1L,
                    messages.get(i).getWatermark(),
                    "Watermark for message %d should be %d".formatted(i + 1, i + 1));
        }

        // Verify strict monotonicity between consecutive messages
        for (int i = 1; i < messages.size(); i++) {
            long prev = messages.get(i - 1).getWatermark();
            long curr = messages.get(i).getWatermark();
            assertTrue(curr > prev, "Watermark %d should be greater than %d".formatted(curr, prev));
            assertEquals(prev + 1, curr, "Watermarks should increment by exactly 1");
        }
    }

    static Stream<Arguments> messageSequenceSizes() {
        return Stream.of(Arguments.of(1), Arguments.of(5), Arguments.of(20));
    }

    // ---- CP 23: Cursor-based pagination ----

    /**
     * Verifies cursor-based pagination with varying page sizes and cursor positions,
     * including empty rooms and rooms with exactly one page of messages.
     *
     * <p>Validates: CP 23 — correct page content, has_more flag, and next_cursor values.
     */
    @ParameterizedTest(name = "[{index}] {3}")
    @MethodSource("paginationScenarios")
    void getMessageHistory_paginationBehavior(
            int totalMessages,
            int pageSize,
            Long cursor,
            String description,
            int expectedCount,
            boolean expectedHasMore) {
        // Send the specified number of messages
        for (int i = 0; i < totalMessages; i++) {
            messageService.sendMessage(room, sender, "Msg " + (i + 1), null);
        }

        // Ascending catch-up pagination (watermark > cursor) is now getMessagesSince (R1-22).
        MessagePage page = messageService.getMessagesSince(room, cursor, pageSize);

        assertEquals(
                expectedCount,
                page.messages().size(),
                "Expected %d messages for: %s".formatted(expectedCount, description));
        assertEquals(
                expectedHasMore,
                page.hasMore(),
                "hasMore should be %s for: %s".formatted(expectedHasMore, description));

        // Verify messages are ordered by watermark ascending
        for (int i = 1; i < page.messages().size(); i++) {
            assertTrue(
                    page.messages().get(i).watermark()
                            > page.messages().get(i - 1).watermark(),
                    "Messages should be ordered by watermark ascending");
        }

        // Verify nextCursor points to the last message's watermark when page is non-empty
        if (!page.messages().isEmpty()) {
            assertEquals(
                    page.messages().getLast().watermark(),
                    page.nextCursor(),
                    "nextCursor should equal the last message's watermark");
        }
    }

    static Stream<Arguments> paginationScenarios() {
        return Stream.of(
                // Empty room
                Arguments.of(0, 10, null, "empty room, null cursor", 0, false),
                Arguments.of(0, 10, 0L, "empty room, cursor=0", 0, false),

                // Exactly one page (no overflow)
                Arguments.of(5, 5, null, "5 messages, pageSize=5, null cursor (exact fit)", 5, false),
                Arguments.of(5, 10, null, "5 messages, pageSize=10, null cursor (under capacity)", 5, false),

                // Multiple pages
                Arguments.of(10, 3, null, "10 messages, pageSize=3, null cursor (first page)", 3, true),
                Arguments.of(10, 3, 3L, "10 messages, pageSize=3, cursor=3 (second page)", 3, true),
                Arguments.of(10, 3, 9L, "10 messages, pageSize=3, cursor=9 (last page, 1 remaining)", 1, false),

                // Single message
                Arguments.of(1, 10, null, "1 message, pageSize=10, null cursor", 1, false),
                Arguments.of(1, 1, null, "1 message, pageSize=1, null cursor (exact fit)", 1, false),

                // Cursor past all messages
                Arguments.of(5, 10, 5L, "5 messages, cursor past last watermark", 0, false),

                // Large page size with few messages
                Arguments.of(3, 100, null, "3 messages, pageSize=100, null cursor", 3, false));
    }

    /**
     * Verifies that iterating through all pages via cursor collects all messages
     * in the correct watermark order.
     *
     * <p>Validates: CP 23 — full iteration through paginated results yields all messages.
     */
    @ParameterizedTest(name = "[{index}] {0} messages, pageSize={1}")
    @MethodSource("fullPaginationIterationScenarios")
    void getMessageHistory_fullIteration_collectsAllMessages(int totalMessages, int pageSize) {
        for (int i = 0; i < totalMessages; i++) {
            messageService.sendMessage(room, sender, "Msg " + (i + 1), null);
        }

        List<Long> collectedWatermarks = new ArrayList<>();
        Long cursor = null;
        boolean hasMore = true;

        while (hasMore) {
            MessagePage page = messageService.getMessagesSince(room, cursor, pageSize);
            page.messages().forEach(m -> collectedWatermarks.add(m.watermark()));
            cursor = page.nextCursor();
            hasMore = page.hasMore();
        }

        assertEquals(
                totalMessages,
                collectedWatermarks.size(),
                "Full iteration should collect all %d messages".formatted(totalMessages));

        // Verify collected watermarks are strictly monotonically increasing
        for (int i = 1; i < collectedWatermarks.size(); i++) {
            assertTrue(
                    collectedWatermarks.get(i) > collectedWatermarks.get(i - 1),
                    "Collected watermarks should be strictly increasing");
        }
    }

    static Stream<Arguments> fullPaginationIterationScenarios() {
        return Stream.of(
                Arguments.of(0, 5), Arguments.of(1, 5), Arguments.of(5, 5), Arguments.of(10, 3), Arguments.of(20, 7));
    }
}
