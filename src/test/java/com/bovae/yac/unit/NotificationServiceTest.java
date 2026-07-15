package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.service.NotificationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for the reworked unread accounting (R1-70, R1-24, R1-57): mark-read is an upsert
 * against the current room watermark, and unread counts derive from undeleted message rows.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private UnreadMarkerRepository unreadMarkerRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private NotificationService notificationService;

    private User user;
    private Room room;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("user@test.com")
                .username("testuser")
                .passwordHash("$2a$10$hash")
                .build();

        roomId = UUID.randomUUID();
        room = Room.builder()
                .id(roomId)
                .name("test-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(user)
                .nextWatermark(10L)
                .build();
    }

    @Test
    void markRoomAsRead_upsertsAtCurrentWatermark() {
        when(roomRepository.nextWatermarkOf(roomId)).thenReturn(10L);

        notificationService.markRoomAsRead(user, room);

        // Latest readable watermark = nextWatermark - 1 = 9.
        verify(unreadMarkerRepository).upsertLastRead(user.getId(), roomId, 9L);
    }

    @Test
    void ensureMarker_insertsAtCurrentWatermarkIfAbsent() {
        when(roomRepository.nextWatermarkOf(roomId)).thenReturn(10L);

        notificationService.ensureMarker(user, room);

        verify(unreadMarkerRepository).insertMarkerIfAbsent(user.getId(), roomId, 9L);
    }

    @Test
    void computeUnreadCount_countsUndeletedRowsPastMarker() {
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(5L)
                .build();
        when(unreadMarkerRepository.findByUserAndRoom(user, room)).thenReturn(Optional.of(marker));
        when(messageRepository.countByRoomAndWatermarkGreaterThan(room, 5L)).thenReturn(4L);

        assertThat(notificationService.computeUnreadCount(user, room)).isEqualTo(4);
    }

    @Test
    void computeUnreadCount_capsAt999() {
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(0L)
                .build();
        when(unreadMarkerRepository.findByUserAndRoom(user, room)).thenReturn(Optional.of(marker));
        when(messageRepository.countByRoomAndWatermarkGreaterThan(room, 0L)).thenReturn(1999L);

        assertThat(notificationService.computeUnreadCount(user, room)).isEqualTo(999);
    }

    @Test
    void computeUnreadCount_zeroWhenNoMarker() {
        when(unreadMarkerRepository.findByUserAndRoom(user, room)).thenReturn(Optional.empty());

        assertThat(notificationService.computeUnreadCount(user, room)).isZero();
    }

    @Test
    void computeUnreadCount_zeroWhenLastReadWatermarkNull() {
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(null)
                .build();
        when(unreadMarkerRepository.findByUserAndRoom(user, room)).thenReturn(Optional.of(marker));

        assertThat(notificationService.computeUnreadCount(user, room)).isZero();
    }
}
