package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.MessagePage;
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
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for watermark monotonicity and cursor-based pagination.
 *
 * Validates: Requirements 16.1, 16.2, 16.4, 16.5
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class WatermarkPropertyTest {

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

    // Feature: online-chat-server, Property 22: Watermark monotonicity and message ordering
    /**
     * Validates: Requirements 16.1, 16.4
     *
     * For any sequence of Messages persisted in a Room, each Message SHALL receive a watermark
     * strictly greater than the previous. Retrieving messages SHALL return them ordered by watermark.
     */
    @Property(tries = 20)
    void watermarkMonotonicityAndMessageOrdering(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll @IntRange(min = 3, max = 10) int messageCount
    ) {
        // Setup: register user, create room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userDto = userService.register(email + suffix, username + suffix, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Watermark test room", RoomVisibility.PUBLIC, user).id());

        // Send N messages and collect their watermarks
        List<Message> sentMessages = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            Message msg = messageService.sendMessage(room, user, "Message " + i, null);
            sentMessages.add(msg);
        }

        // Each watermark SHALL be strictly greater than the previous
        for (int i = 1; i < sentMessages.size(); i++) {
            Long prevWatermark = sentMessages.get(i - 1).getWatermark();
            Long currWatermark = sentMessages.get(i).getWatermark();
            assertThat(currWatermark)
                    .as("Watermark at index %d (%d) should be strictly greater than at index %d (%d)",
                            i, currWatermark, i - 1, prevWatermark)
                    .isGreaterThan(prevWatermark);
        }

        // Retrieving messages SHALL return them ordered by watermark ascending
        MessagePage page = messageService.getMessageHistory(room, 0L, messageCount + 10);
        List<ChatMessageResponse> retrieved = page.messages();

        assertThat(retrieved).hasSize(messageCount);

        for (int i = 1; i < retrieved.size(); i++) {
            assertThat(retrieved.get(i).watermark())
                    .as("Retrieved message at index %d should have watermark > message at index %d", i, i - 1)
                    .isGreaterThan(retrieved.get(i - 1).watermark());
        }

        // Watermarks from retrieval SHALL match the watermarks assigned at send time
        for (int i = 0; i < messageCount; i++) {
            assertThat(retrieved.get(i).watermark()).isEqualTo(sentMessages.get(i).getWatermark());
        }
    }

    // Feature: online-chat-server, Property 23: Cursor-based pagination correctness
    /**
     * Validates: Requirements 16.2, 16.5
     *
     * For any Room with N messages and a cursor value C, querying messages with cursor C and
     * page size S SHALL return exactly the messages with watermark > C, limited to S results,
     * ordered by watermark ascending. The response SHALL include a nextCursor pointing to the
     * last returned watermark, and hasMore SHALL be true iff more messages exist beyond the page.
     */
    @Property(tries = 20)
    void cursorBasedPaginationCorrectness(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll @IntRange(min = 5, max = 15) int messageCount
    ) {
        // Setup: register user, create room, send N messages
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userDto = userService.register(email + suffix, username + suffix, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Pagination test room", RoomVisibility.PUBLIC, user).id());

        List<Message> sentMessages = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            Message msg = messageService.sendMessage(room, user, "Paginated message " + i, null);
            sentMessages.add(msg);
        }

        // --- Test 1: Paginate from the beginning with a small page size ---
        int pageSize = 3;
        MessagePage firstPage = messageService.getMessageHistory(room, 0L, pageSize);

        // SHALL return exactly pageSize messages (since messageCount >= 5 > 3)
        assertThat(firstPage.messages()).hasSize(pageSize);

        // Messages SHALL be ordered by watermark ascending
        for (int i = 1; i < firstPage.messages().size(); i++) {
            assertThat(firstPage.messages().get(i).watermark())
                    .isGreaterThan(firstPage.messages().get(i - 1).watermark());
        }

        // All returned messages SHALL have watermark > cursor (0)
        for (ChatMessageResponse msg : firstPage.messages()) {
            assertThat(msg.watermark()).isGreaterThan(0L);
        }

        // nextCursor SHALL point to the last returned watermark
        Long expectedNextCursor = firstPage.messages().getLast().watermark();
        assertThat(firstPage.nextCursor()).isEqualTo(expectedNextCursor);

        // hasMore SHALL be true since messageCount > pageSize
        assertThat(firstPage.hasMore()).isTrue();

        // --- Test 2: Use nextCursor to fetch the next page ---
        MessagePage secondPage = messageService.getMessageHistory(room, firstPage.nextCursor(), pageSize);

        // All returned messages SHALL have watermark > firstPage.nextCursor
        for (ChatMessageResponse msg : secondPage.messages()) {
            assertThat(msg.watermark()).isGreaterThan(firstPage.nextCursor());
        }

        // Messages SHALL be ordered by watermark ascending
        for (int i = 1; i < secondPage.messages().size(); i++) {
            assertThat(secondPage.messages().get(i).watermark())
                    .isGreaterThan(secondPage.messages().get(i - 1).watermark());
        }

        // nextCursor SHALL point to the last returned watermark
        if (!secondPage.messages().isEmpty()) {
            assertThat(secondPage.nextCursor())
                    .isEqualTo(secondPage.messages().getLast().watermark());
        }

        // --- Test 3: Paginate through ALL messages and verify completeness ---
        List<ChatMessageResponse> allCollected = new ArrayList<>();
        Long cursor = 0L;
        boolean hasMore = true;

        while (hasMore) {
            MessagePage page = messageService.getMessageHistory(room, cursor, pageSize);
            allCollected.addAll(page.messages());
            hasMore = page.hasMore();
            if (!page.messages().isEmpty()) {
                cursor = page.nextCursor();
            }
        }

        // Total collected messages SHALL equal the number sent
        assertThat(allCollected).hasSize(messageCount);

        // All collected messages SHALL be in watermark ascending order
        for (int i = 1; i < allCollected.size(); i++) {
            assertThat(allCollected.get(i).watermark())
                    .isGreaterThan(allCollected.get(i - 1).watermark());
        }

        // --- Test 4: Cursor beyond all messages returns empty with hasMore=false ---
        Long maxWatermark = sentMessages.getLast().getWatermark();
        MessagePage emptyPage = messageService.getMessageHistory(room, maxWatermark, pageSize);
        assertThat(emptyPage.messages()).isEmpty();
        assertThat(emptyPage.hasMore()).isFalse();

        // --- Test 5: Page size larger than remaining messages ---
        // Use cursor from the first message to get all remaining
        Long firstWatermark = sentMessages.getFirst().getWatermark();
        int largePage = messageCount + 10;
        MessagePage bigPage = messageService.getMessageHistory(room, firstWatermark, largePage);

        // SHALL return exactly messageCount - 1 messages (all after the first)
        assertThat(bigPage.messages()).hasSize(messageCount - 1);

        // hasMore SHALL be false since all remaining messages fit in the page
        assertThat(bigPage.hasMore()).isFalse();
    }
}
