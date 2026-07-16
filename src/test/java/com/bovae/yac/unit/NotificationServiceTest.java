package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.service.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    // --- markRoomAsRead keeps any cached marker consistent with the upserted watermark ---

    /**
     * The native upsert bypasses the persistence context, so a same-transaction cached marker is
     * advanced in-memory only when it lags the new watermark (null or below), never regressed.
     */
    @ParameterizedTest(name = "existing={0} → cached={1}")
    @MethodSource("cachedMarkerCases")
    void markRoomAsRead_advancesCachedMarkerOnlyWhenStale(Long existing, long expectedCached) {
        when(roomRepository.nextWatermarkOf(roomId)).thenReturn(10L);
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(existing)
                .build();
        when(unreadMarkerRepository.findByUserAndRoom(user, room)).thenReturn(Optional.of(marker));

        notificationService.markRoomAsRead(user, room);

        verify(unreadMarkerRepository).upsertLastRead(user.getId(), roomId, 9L);
        assertThat(marker.getLastReadWatermark()).isEqualTo(expectedCached);
    }

    static Stream<Arguments> cachedMarkerCases() {
        return Stream.of(Arguments.of(null, 9L), Arguments.of(5L, 9L), Arguments.of(20L, 20L));
    }

    // --- computeUnreadCounts (batch) ---

    /**
     * The grouped per-user count query (R4-03) drives the map: a member present in the result
     * gets that count; a member absent (no marker, or zero unread) gets zero.
     */
    @Test
    void computeUnreadCounts_mapsGroupedCounts_absentMembersCountZero() {
        User other = otherUser();
        RoomMember memberUser = RoomMember.builder().room(room).user(user).build();
        RoomMember memberOther = RoomMember.builder().room(room).user(other).build();

        // Only `user` appears in the grouped result; `other` is absent → 0.
        when(messageRepository.countUnreadPerUserInRoom(roomId))
                .thenReturn(List.<Object[]>of(new Object[] {user.getId(), 3L}));

        Map<UUID, Integer> counts = notificationService.computeUnreadCounts(room, List.of(memberUser, memberOther));

        assertThat(counts.get(user.getId())).isEqualTo(3);
        assertThat(counts.get(other.getId())).isZero();
    }

    /** With an empty grouped result, every member's batch count is zero. */
    @Test
    void computeUnreadCounts_allZero_whenGroupedResultEmpty() {
        RoomMember memberUser = RoomMember.builder().room(room).user(user).build();
        when(messageRepository.countUnreadPerUserInRoom(roomId)).thenReturn(List.of());

        Map<UUID, Integer> counts = notificationService.computeUnreadCounts(room, List.of(memberUser));

        assertThat(counts.get(user.getId())).isZero();
    }

    /** Counts above the DISPLAY_CAP (999) are clamped. */
    @Test
    void computeUnreadCounts_clampsToDisplayCap() {
        RoomMember memberUser = RoomMember.builder().room(room).user(user).build();
        when(messageRepository.countUnreadPerUserInRoom(roomId))
                .thenReturn(List.<Object[]>of(new Object[] {user.getId(), 5000L}));

        Map<UUID, Integer> counts = notificationService.computeUnreadCounts(room, List.of(memberUser));

        assertThat(counts.get(user.getId())).isEqualTo(999);
    }

    // --- helpers ---

    private static User otherUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("other@test.com")
                .username("other")
                .passwordHash("$2a$10$hash")
                .build();
    }
}
