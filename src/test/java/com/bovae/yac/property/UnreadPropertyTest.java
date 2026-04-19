package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.NotificationService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for NotificationService unread count computation.
 *
 * Validates: Requirements 17.1, 17.2, 17.4
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class UnreadPropertyTest {

    @Autowired
    private NotificationService notificationService;

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

    @Autowired
    private UnreadMarkerRepository unreadMarkerRepository;

    @AfterTry
    void cleanup() {
        unreadMarkerRepository.deleteAll();
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

    // Feature: online-chat-server, Property 24: Unread count computation
    /**
     * Validates: Requirements 17.1, 17.2, 17.4
     *
     * For any User and Room, the unread count SHALL equal
     * min(room.nextWatermark - 1 - lastReadWatermark, displayCap).
     * Opening the Room SHALL update lastReadWatermark to the current watermark,
     * making the unread count zero.
     */
    @Property(tries = 4)
    void unreadCountComputation(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll @IntRange(min = 1, max = 10) int firstBatch,
            @ForAll @IntRange(min = 1, max = 10) int secondBatch
    ) {
        // Setup: register user, create room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto ownerDto = userService.register(email + suffix, username + suffix, password);
        User owner = userRepository.findById(ownerDto.id()).orElseThrow();
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Unread test room", RoomVisibility.PUBLIC, owner).id());

        // Step 1: Send firstBatch messages
        for (int i = 0; i < firstBatch; i++) {
            messageService.sendMessage(room, owner, "First batch message " + i, null);
        }

        // Reload room to get updated nextWatermark
        room = roomRepository.findById(room.getId()).orElseThrow();

        // Step 2: Compute unread for owner with no marker — should be 0 (no marker = all read)
        int unreadNoMarker = notificationService.computeUnreadCount(owner, room);
        assertThat(unreadNoMarker)
                .as("Unread count with no marker should be 0")
                .isEqualTo(0);

        // Step 3: Mark room as read (simulates opening the room)
        notificationService.markRoomAsRead(owner, room);

        // Reload room again
        room = roomRepository.findById(room.getId()).orElseThrow();

        // After marking as read, unread count SHALL be zero
        int unreadAfterRead = notificationService.computeUnreadCount(owner, room);
        assertThat(unreadAfterRead)
                .as("Unread count after marking as read should be 0")
                .isEqualTo(0);

        // Step 4: Send secondBatch more messages
        for (int i = 0; i < secondBatch; i++) {
            messageService.sendMessage(room, owner, "Second batch message " + i, null);
        }

        // Reload room to get updated nextWatermark
        room = roomRepository.findById(room.getId()).orElseThrow();

        // Step 5: Compute unread → should equal number of new messages
        int unreadAfterNewMessages = notificationService.computeUnreadCount(owner, room);
        assertThat(unreadAfterNewMessages)
                .as("Unread count should equal the number of new messages sent after marking as read")
                .isEqualTo(secondBatch);

        // Verify the formula: min(room.nextWatermark - 1 - lastReadWatermark, displayCap)
        long expectedUnread = room.getNextWatermark() - 1 - (room.getNextWatermark() - 1 - secondBatch);
        assertThat(unreadAfterNewMessages)
                .as("Unread count should match formula: min(nextWatermark - 1 - lastReadWatermark, displayCap)")
                .isEqualTo((int) Math.min(expectedUnread, 999));

        // Step 6: Mark room as read again → unread should be 0
        notificationService.markRoomAsRead(owner, room);
        room = roomRepository.findById(room.getId()).orElseThrow();

        int unreadAfterSecondRead = notificationService.computeUnreadCount(owner, room);
        assertThat(unreadAfterSecondRead)
                .as("Unread count after marking as read again should be 0")
                .isEqualTo(0);
    }
}
