package com.bovae.yac.unit;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationService}.
 *
 * <p>Validates Correctness Property: CP 24.
 * <p>Requirements: 4.8, 4.9.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private UnreadMarkerRepository unreadMarkerRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private NotificationService notificationService;

    private User user;
    private Room room;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("user@test.com")
                .username("testuser")
                .passwordHash("$2a$10$hash")
                .build();

        room = Room.builder()
                .id(UUID.randomUUID())
                .name("test-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(user)
                .nextWatermark(10L)
                .build();
    }

    /**
     * Validates CP 24: markRoomAsRead sets lastReadWatermark to the current room
     * watermark minus one (i.e. room.nextWatermark - 1).
     */
    @Test
    void markRoomAsRead_setsLastReadWatermarkToCurrentRoomWatermarkMinusOne() {
        when(unreadMarkerRepository.findByUserAndRoom(user, room))
                .thenReturn(Optional.empty());
        when(unreadMarkerRepository.save(any(UnreadMarker.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        notificationService.markRoomAsRead(user, room);

        ArgumentCaptor<UnreadMarker> captor = ArgumentCaptor.forClass(UnreadMarker.class);
        verify(unreadMarkerRepository).save(captor.capture());

        UnreadMarker saved = captor.getValue();
        assertThat(saved.getLastReadWatermark()).isEqualTo(room.getNextWatermark() - 1);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getRoom()).isEqualTo(room);
    }

    /**
     * Validates CP 24: markRoomAsRead updates an existing marker's lastReadWatermark
     * to the current room watermark minus one.
     */
    @Test
    void markRoomAsRead_withExistingMarker_updatesLastReadWatermark() {
        UnreadMarker existingMarker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(3L)
                .build();

        when(unreadMarkerRepository.findByUserAndRoom(user, room))
                .thenReturn(Optional.of(existingMarker));
        when(unreadMarkerRepository.save(any(UnreadMarker.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        notificationService.markRoomAsRead(user, room);

        ArgumentCaptor<UnreadMarker> captor = ArgumentCaptor.forClass(UnreadMarker.class);
        verify(unreadMarkerRepository).save(captor.capture());

        UnreadMarker saved = captor.getValue();
        assertThat(saved.getLastReadWatermark()).isEqualTo(room.getNextWatermark() - 1);
    }

    /**
     * Validates CP 24: computeUnreadCount returns
     * min(room.nextWatermark - 1 - lastReadWatermark, 999).
     */
    @Test
    void computeUnreadCount_returnsCorrectUnreadCount() {
        // room.nextWatermark = 10, lastReadWatermark = 5
        // unread = 10 - 1 - 5 = 4
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(5L)
                .build();

        when(unreadMarkerRepository.findByUserAndRoom(user, room))
                .thenReturn(Optional.of(marker));

        int unreadCount = notificationService.computeUnreadCount(user, room);

        assertThat(unreadCount).isEqualTo(4);
    }

    /**
     * Validates CP 24: computeUnreadCount caps at 999 when unread exceeds display cap.
     */
    @Test
    void computeUnreadCount_capsAt999WhenUnreadExceedsDisplayCap() {
        // room.nextWatermark = 2000, lastReadWatermark = 0
        // unread = 2000 - 1 - 0 = 1999, capped to 999
        room.setNextWatermark(2000L);

        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(0L)
                .build();

        when(unreadMarkerRepository.findByUserAndRoom(user, room))
                .thenReturn(Optional.of(marker));

        int unreadCount = notificationService.computeUnreadCount(user, room);

        assertThat(unreadCount).isEqualTo(999);
    }

    /**
     * Validates CP 24: computeUnreadCount returns 0 when no marker exists.
     */
    @Test
    void computeUnreadCount_returnsZeroWhenNoMarkerExists() {
        when(unreadMarkerRepository.findByUserAndRoom(user, room))
                .thenReturn(Optional.empty());

        int unreadCount = notificationService.computeUnreadCount(user, room);

        assertThat(unreadCount).isEqualTo(0);
    }

    /**
     * Validates CP 24: computeUnreadCount returns 0 when lastReadWatermark is null.
     */
    @Test
    void computeUnreadCount_returnsZeroWhenLastReadWatermarkIsNull() {
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(null)
                .build();

        when(unreadMarkerRepository.findByUserAndRoom(user, room))
                .thenReturn(Optional.of(marker));

        int unreadCount = notificationService.computeUnreadCount(user, room);

        assertThat(unreadCount).isEqualTo(0);
    }

    /**
     * Validates CP 24: computeUnreadCount returns 0 when all messages are read
     * (lastReadWatermark equals room.nextWatermark - 1).
     */
    @Test
    void computeUnreadCount_returnsZeroWhenAllMessagesAreRead() {
        // room.nextWatermark = 10, lastReadWatermark = 9
        // unread = 10 - 1 - 9 = 0
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(9L)
                .build();

        when(unreadMarkerRepository.findByUserAndRoom(user, room))
                .thenReturn(Optional.of(marker));

        int unreadCount = notificationService.computeUnreadCount(user, room);

        assertThat(unreadCount).isEqualTo(0);
    }
}
