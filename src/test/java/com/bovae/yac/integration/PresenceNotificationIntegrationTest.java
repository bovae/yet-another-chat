package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.PresenceService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for presence heartbeat flows and unread notification computation.
 *
 * <p>Validates Requirements: 8.1, 8.2, 8.3, 8.4, 8.5
 * <p>Validates Correctness Properties: CP 9, CP 24
 *
 * <p>Note: Presence tests use Redis, so {@code @Transactional} won't roll back Redis state.
 * Redis cleanup is handled in {@code @AfterEach}.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class PresenceNotificationIntegrationTest {

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private UserRepository userRepository;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("presence-a@test.com", "presencea", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register("presence-b@test.com", "presenceb", "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
    }

    @AfterEach
    void cleanup() {
        // Clean up Redis presence keys (not covered by @Transactional rollback)
        presenceService.removePresence(userA.getId());
        presenceService.removePresence(userB.getId());
    }

    // ---- Heartbeat → status change flow ----

    /**
     * Validates Requirement 8.1: Active heartbeat changes user status to ONLINE.
     * Validates Correctness Property 9.
     */
    @Test
    void activeHeartbeat_changesStatusToOnline() {
        // Initially, user should be OFFLINE (no heartbeats)
        PresenceStatus initialStatus = presenceService.getUserStatus(userA.getId());
        assertThat(initialStatus).isEqualTo(PresenceStatus.OFFLINE);

        // Record an active heartbeat
        presenceService.recordHeartbeat(userA.getId(), true);

        // Status should now be ONLINE
        PresenceStatus afterHeartbeat = presenceService.getUserStatus(userA.getId());
        assertThat(afterHeartbeat).isEqualTo(PresenceStatus.ONLINE);
    }

    /**
     * Validates Requirement 8.2: Inactive heartbeat changes user status to AFK.
     * Validates Correctness Property 9.
     */
    @Test
    void inactiveHeartbeat_changesStatusToAfk() {
        // Record an inactive heartbeat
        presenceService.recordHeartbeat(userA.getId(), false);

        // With an inactive heartbeat, status should be AFK
        PresenceStatus status = presenceService.getUserStatus(userA.getId());
        assertThat(status).isEqualTo(PresenceStatus.AFK);
    }

    /**
     * Validates Requirement 8.3: Removing presence sets user status to OFFLINE.
     * Validates Correctness Property 9.
     */
    @Test
    void removePresence_setsStatusToOffline() {
        // Record active heartbeat first
        presenceService.recordHeartbeat(userA.getId(), true);
        assertThat(presenceService.getUserStatus(userA.getId())).isEqualTo(PresenceStatus.ONLINE);

        // Remove presence
        presenceService.removePresence(userA.getId());

        // Should be OFFLINE
        assertThat(presenceService.getUserStatus(userA.getId())).isEqualTo(PresenceStatus.OFFLINE);
    }

    // ---- Unread count computation ----

    /**
     * Validates Requirement 8.4: Sending messages increments unread count for non-viewing members.
     * Validates Correctness Property 24.
     */
    @Test
    void sendingMessages_incrementsUnreadCountForNonViewingMembers() {
        // Create a room with userA as owner
        Room room = roomService.getRoomById(roomService.createRoom("unread-incr-room", "test", RoomVisibility.PUBLIC, userA).id());

        // UserB joins the room
        roomMemberService.joinPublicRoom(room, userB);

        // Mark room as read for userB (initial state)
        Room freshRoom = roomService.getRoomById(room.getId());
        notificationService.markRoomAsRead(userB, freshRoom);

        // Verify unread count is 0
        freshRoom = roomService.getRoomById(room.getId());
        int unreadBefore = notificationService.computeUnreadCount(userB, freshRoom);
        assertThat(unreadBefore).isZero();

        // UserA sends 3 messages
        for (int i = 1; i <= 3; i++) {
            freshRoom = roomService.getRoomById(room.getId());
            messageService.sendMessage(freshRoom, userA, "Message " + i, null);
        }

        // Compute unread count for userB — should be 3
        freshRoom = roomService.getRoomById(room.getId());
        int unreadAfterMessages = notificationService.computeUnreadCount(userB, freshRoom);
        assertThat(unreadAfterMessages).isEqualTo(3);
    }

    /**
     * Validates Requirement 8.5: Opening a room resets unread count to zero.
     * Validates Correctness Property 24.
     */
    @Test
    void openingRoom_resetsUnreadCountToZero() {
        // Create a room with userA as owner
        Room room = roomService.getRoomById(roomService.createRoom("unread-reset-room", "test", RoomVisibility.PUBLIC, userA).id());

        // UserB joins the room
        roomMemberService.joinPublicRoom(room, userB);

        // Mark room as read for userB (initial state)
        Room freshRoom = roomService.getRoomById(room.getId());
        notificationService.markRoomAsRead(userB, freshRoom);

        // UserA sends messages to create unread count
        for (int i = 1; i <= 5; i++) {
            freshRoom = roomService.getRoomById(room.getId());
            messageService.sendMessage(freshRoom, userA, "Message " + i, null);
        }

        // Verify unread count is non-zero
        freshRoom = roomService.getRoomById(room.getId());
        int unreadBeforeOpen = notificationService.computeUnreadCount(userB, freshRoom);
        assertThat(unreadBeforeOpen).isEqualTo(5);

        // UserB opens the room (mark as read)
        freshRoom = roomService.getRoomById(room.getId());
        notificationService.markRoomAsRead(userB, freshRoom);

        // Unread count should be 0
        freshRoom = roomService.getRoomById(room.getId());
        int unreadAfterOpen = notificationService.computeUnreadCount(userB, freshRoom);
        assertThat(unreadAfterOpen).isZero();
    }
}
