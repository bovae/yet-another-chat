package com.bovae.yac.service;

import com.bovae.yac.model.dto.NotificationEvent;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UnreadMarkerRepository;
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
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void markRoomAsRead(User user, Room room) {
        long currentWatermark = room.getNextWatermark() - 1;

        UnreadMarker marker = unreadMarkerRepository.findByUserAndRoom(user, room)
                .orElse(null);

        if (marker == null) {
            marker = UnreadMarker.builder()
                    .user(user)
                    .room(room)
                    .lastReadWatermark(currentWatermark)
                    .build();
        } else {
            marker.setLastReadWatermark(currentWatermark);
        }

        unreadMarkerRepository.save(marker);

        LOG.debug("Marked room as read: userId={}, roomId={}, watermark={}",
                user.getId(), room.getId(), currentWatermark);
    }

    public int computeUnreadCount(User user, Room room) {
        UnreadMarker marker = unreadMarkerRepository.findByUserAndRoom(user, room)
                .orElse(null);

        if (marker == null || marker.getLastReadWatermark() == null) {
            return 0;
        }

        long unread = room.getNextWatermark() - 1 - marker.getLastReadWatermark();
        if (unread < 0) {
            return 0;
        }

        return (int) Math.min(unread, DISPLAY_CAP);
    }

    public void broadcastNotification(User user, NotificationEvent event) {
        messagingTemplate.convertAndSendToUser(
                user.getEmail(),
                "/queue/notifications",
                event
        );

        LOG.debug("Notification sent: userId={}, type={}, roomId={}, unreadCount={}",
                user.getId(), event.type(), event.roomId(), event.unreadCount());
    }
}
