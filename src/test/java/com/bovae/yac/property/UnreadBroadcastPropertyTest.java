package com.bovae.yac.property;

import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.NotificationEvent;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.ws.ChatMessageHandler;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for unread notification broadcast on new message.
 *
 * Property 8: Unread notification broadcast on new message
 *
 * For any room with N members, when a message is sent by one member, exactly N-1
 * NotificationEvent messages of type UNREAD_UPDATE SHALL be broadcast — one to each
 * non-sender member's personal WebSocket queue. Each event SHALL contain the room's ID
 * and the recipient's current unread count.
 *
 * Validates: Requirements 9.1, 9.2, 9.3
 */
class UnreadBroadcastPropertyTest {

    private SimpMessagingTemplate messagingTemplate;
    private MessageService messageService;
    private RoomRepository roomRepository;
    private UserRepository userRepository;
    private MessageRepository messageRepository;
    private RoomMemberRepository roomMemberRepository;
    private NotificationService notificationService;
    private ChatMessageHandler handler;

    @BeforeTry
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        messageService = mock(MessageService.class);
        roomRepository = mock(RoomRepository.class);
        userRepository = mock(UserRepository.class);
        messageRepository = mock(MessageRepository.class);
        roomMemberRepository = mock(RoomMemberRepository.class);
        notificationService = mock(NotificationService.class);
        handler = new ChatMessageHandler(
                messagingTemplate, messageService, roomRepository,
                userRepository, messageRepository, roomMemberRepository,
                notificationService
        );
    }

    @Provide
    Arbitrary<Integer> memberCounts() {
        return Arbitraries.integers().between(2, 20);
    }

    private User buildUser(int index) {
        return User.builder()
                .id(UUID.randomUUID())
                .email("user" + index + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com")
                .username("user" + index + "_" + UUID.randomUUID().toString().substring(0, 6))
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

    /**
     * Property 8: For any room with N members, when a message is sent by one member,
     * exactly N-1 broadcastNotification calls SHALL be made — one to each non-sender member.
     * Each NotificationEvent SHALL have type UNREAD_UPDATE and contain the room's ID.
     *
     * Validates: Requirements 9.1, 9.2, 9.3
     */
    @Property(tries = 20)
    void broadcastNotification_shallBeSentToExactlyNMinus1NonSenderMembers(
            @ForAll("memberCounts") int memberCount
    ) {
        // Build room and members
        Room room = buildRoom();
        List<User> users = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            users.add(buildUser(i));
        }

        // Pick a random sender (use first user for determinism within the property)
        int senderIndex = memberCount / 2;
        User sender = users.get(senderIndex);

        // Build RoomMember list
        List<RoomMember> members = users.stream()
                .map(u -> RoomMember.builder()
                        .room(room)
                        .user(u)
                        .role(RoomRole.MEMBER)
                        .joinedAt(Instant.now())
                        .build())
                .toList();

        // Stub dependencies
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(userRepository.findByEmail(sender.getEmail())).thenReturn(Optional.of(sender));
        when(roomMemberRepository.findByRoomWithUsers(room)).thenReturn(members);

        Message sentMessage = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("test message")
                .edited(false)
                .watermark(10L)
                .build();
        when(messageService.sendMessage(eq(room), eq(sender), any(), any())).thenReturn(sentMessage);

        // Stub unread counts — each non-sender gets a unique count based on index
        for (int i = 0; i < users.size(); i++) {
            if (i != senderIndex) {
                when(notificationService.computeUnreadCount(users.get(i), room)).thenReturn(i + 1);
            }
        }

        // Execute
        ChatMessageRequest request = new ChatMessageRequest(room.getId(), "test message", null);
        Principal principal = () -> sender.getEmail();
        handler.sendMessage(request, principal);

        // Capture all broadcastNotification calls
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, times(memberCount - 1))
                .broadcastNotification(userCaptor.capture(), eventCaptor.capture());

        List<User> capturedUsers = userCaptor.getAllValues();
        List<NotificationEvent> capturedEvents = eventCaptor.getAllValues();

        // Verify exactly N-1 broadcasts
        assertThat(capturedUsers).hasSize(memberCount - 1);
        assertThat(capturedEvents).hasSize(memberCount - 1);

        // Verify sender is NOT among the recipients
        Set<UUID> recipientIds = capturedUsers.stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        assertThat(recipientIds).doesNotContain(sender.getId());

        // Verify all non-sender members received exactly one notification
        Set<UUID> expectedRecipientIds = users.stream()
                .filter(u -> !u.getId().equals(sender.getId()))
                .map(User::getId)
                .collect(Collectors.toSet());
        assertThat(recipientIds).isEqualTo(expectedRecipientIds);

        // Verify each event has type UNREAD_UPDATE and the correct room ID
        for (NotificationEvent event : capturedEvents) {
            assertThat(event.type()).isEqualTo("UNREAD_UPDATE");
            assertThat(event.roomId()).isEqualTo(room.getId());
        }

        // Verify each event contains the correct unread count for its recipient
        for (int i = 0; i < capturedUsers.size(); i++) {
            User recipient = capturedUsers.get(i);
            NotificationEvent event = capturedEvents.get(i);
            int expectedUnread = users.indexOf(recipient) + 1;
            assertThat(event.unreadCount()).isEqualTo(expectedUnread);
        }
    }
}
