package com.bovae.yac.service;

import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.MessageEditedEvent;
import com.bovae.yac.model.dto.NotificationEvent;
import com.bovae.yac.model.dto.RoomEvent;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single source of truth for realtime fan-out. Every message-producing path — WS send,
 * REST send, attachment upload, edit — routes through here so viewers and unread badges
 * stay consistent (R1-03, R1-04, R1-21; design D1).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;
    private final RoomMemberRepository roomMemberRepository;
    private final NotificationService notificationService;

    /** Broadcasts a persisted message to the room topic and fans out unread updates to every other member. */
    public void broadcastNewMessage(Room room, User sender, ChatMessageResponse response) {
        messagingTemplate.convertAndSend("/topic/room." + room.getId(), response);
        notificationService.markRoomAsRead(sender, room);
        fanOutUnread(room, sender);
        LOG.debug("Broadcast message to room {}: messageId={}", room.getId(), response.id());
    }

    /** Recomputes and re-broadcasts unread counts to all members after a deletion (R1-57). */
    public void recomputeUnread(Room room) {
        fanOutUnread(room, null);
    }

    /** Broadcasts an in-place edit so viewers update the text instead of dropping it as a duplicate (R1-04). */
    public void broadcastEdit(Room room, UUID messageId, String content) {
        messagingTemplate.convertAndSend("/topic/room." + room.getId(),
                MessageEditedEvent.of(messageId, room.getId(), content));
        LOG.debug("Broadcast edit to room {}: messageId={}", room.getId(), messageId);
    }

    /**
     * Broadcasts a membership change to room viewers and directs a copy to the affected user's
     * personal queue so a kicked/banned client reacts without a reload (R1-21).
     *
     * @param type one of {@code MEMBER_JOINED}, {@code MEMBER_LEFT}, {@code MEMBER_BANNED}
     */
    public void broadcastMembership(Room room, User affectedUser, String type) {
        RoomEvent event = new RoomEvent(type, room.getId(), affectedUser.getId(), affectedUser.getUsername());
        messagingTemplate.convertAndSend("/topic/room." + room.getId() + ".events", event);
        messagingTemplate.convertAndSendToUser(affectedUser.getEmail(), "/queue/notifications", event);
        LOG.debug("Broadcast {} for room {}: userId={}", type, room.getId(), affectedUser.getId());
    }

    // Aggregate fan-out: one marker fetch + one watermark fetch, counted in memory (R1-44).
    private void fanOutUnread(Room room, User excludeUser) {
        List<RoomMember> members = roomMemberRepository.findByRoomWithUsers(room);
        Map<UUID, Integer> counts = notificationService.computeUnreadCounts(room, members);
        for (RoomMember member : members) {
            User recipient = member.getUser();
            if (excludeUser != null && recipient.getId().equals(excludeUser.getId())) {
                continue;
            }
            int unread = counts.getOrDefault(recipient.getId(), 0);
            notificationService.broadcastNotification(recipient,
                    new NotificationEvent("UNREAD_UPDATE", room.getId(), room.getName(), unread));
        }
    }
}
