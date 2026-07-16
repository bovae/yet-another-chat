package com.bovae.yac.service;

import com.bovae.yac.model.dto.NotificationEvent;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private static final int DISPLAY_CAP = 999;

    private final UnreadMarkerRepository unreadMarkerRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /** Idempotent read acknowledgement — upsert with GREATEST so races never 500 or regress (R1-70). */
    @Transactional
    public void markRoomAsRead(User user, Room room) {
        long currentWatermark = latestWatermark(room);
        unreadMarkerRepository.upsertLastRead(user.getId(), room.getId(), currentWatermark);
        // The native upsert bypasses the persistence context; keep any cached marker consistent
        // so a same-transaction read reflects the new watermark.
        unreadMarkerRepository.findByUserAndRoom(user, room).ifPresent(marker -> {
            Long existing = marker.getLastReadWatermark();
            if (existing == null || existing < currentWatermark) {
                marker.setLastReadWatermark(currentWatermark);
            }
        });
        LOG.debug(
                "Marked room as read: userId={}, roomId={}, watermark={}",
                user.getId(),
                room.getId(),
                currentWatermark);
    }

    /** Creates a read marker at join/DM-create so never-opened rooms still accumulate unread (R1-24). */
    @Transactional
    public void ensureMarker(User user, Room room) {
        long currentWatermark = latestWatermark(room);
        unreadMarkerRepository.insertMarkerIfAbsent(user.getId(), room.getId(), currentWatermark);
    }

    @Transactional(readOnly = true)
    public int computeUnreadCount(User user, Room room) {
        UnreadMarker marker =
                unreadMarkerRepository.findByUserAndRoom(user, room).orElse(null);
        if (marker == null || marker.getLastReadWatermark() == null) {
            return 0;
        }
        long unread = messageRepository.countByRoomAndWatermarkGreaterThan(room, marker.getLastReadWatermark());
        return (int) Math.min(unread, DISPLAY_CAP);
    }

    /**
     * Batch unread counts for a room's members in a constant number of queries (R1-44): a single
     * grouped count aggregated by the database, so the work is bounded by the member count rather
     * than the room's message history (R4-03). Counts undeleted rows so deletions do not inflate
     * the badge (R1-57).
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> computeUnreadCounts(Room room, List<RoomMember> members) {
        Map<UUID, Long> unreadByUser = messageRepository.countUnreadPerUserInRoom(room.getId()).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));

        Map<UUID, Integer> counts = new HashMap<>();
        for (RoomMember member : members) {
            UUID userId = member.getUser().getId();
            // A member with no marker or zero unread is absent from the grouped result → 0.
            long count = unreadByUser.getOrDefault(userId, 0L);
            counts.put(userId, (int) Math.min(count, DISPLAY_CAP));
        }
        return counts;
    }

    public void broadcastNotification(User user, NotificationEvent event) {
        messagingTemplate.convertAndSendToUser(user.getEmail(), "/queue/notifications", event);
        LOG.debug(
                "Notification sent: userId={}, type={}, roomId={}, unreadCount={}",
                user.getId(),
                event.type(),
                event.roomId(),
                event.unreadCount());
    }

    // The room entity may hold a stale next_watermark after native increments, so read it fresh.
    private long latestWatermark(Room room) {
        return roomRepository.nextWatermarkOf(room.getId()) - 1;
    }
}
