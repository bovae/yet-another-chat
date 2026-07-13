package com.bovae.yac.ws;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceVisibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inbound STOMP authorization (design D4): rejects unauthenticated CONNECT (R1-62) and
 * authorizes SUBSCRIBE frames against room membership (R1-09) and presence visibility (R1-65).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern ROOM_TOPIC =
            Pattern.compile("^/topic/room\\.([0-9a-fA-F-]{36})(?:\\.events)?$");
    private static final Pattern PRESENCE_TOPIC =
            Pattern.compile("^/topic/presence\\.([0-9a-fA-F-]{36})$");

    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final PresenceVisibilityService presenceVisibilityService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            if (accessor.getUser() == null) {
                LOG.warn("Rejected unauthenticated WebSocket CONNECT");
                throw new MessageDeliveryException("Unauthenticated WebSocket connection rejected");
            }
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
        }

        return message;
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new MessageDeliveryException("Authentication required to subscribe");
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        Matcher roomMatcher = ROOM_TOPIC.matcher(destination);
        if (roomMatcher.matches()) {
            authorizeRoom(principal, UUID.fromString(roomMatcher.group(1)));
            return;
        }
        Matcher presenceMatcher = PRESENCE_TOPIC.matcher(destination);
        if (presenceMatcher.matches()) {
            authorizePresence(principal, UUID.fromString(presenceMatcher.group(1)));
        }
        // Personal queues (/user/**, /queue/**) are already gated to authenticated users.
    }

    private void authorizeRoom(Principal principal, UUID roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new MessageDeliveryException("Room not found"));
        if (room.getVisibility() == RoomVisibility.PUBLIC) {
            return; // public rooms follow the same visibility rule as history reads
        }
        User user = resolveUser(principal);
        if (!roomMemberRepository.existsByRoomAndUser(room, user)) {
            LOG.warn("Rejected SUBSCRIBE to room {} by non-member {}", roomId, principal.getName());
            throw new MessageDeliveryException("Not a member of this room");
        }
    }

    private void authorizePresence(Principal principal, UUID targetUserId) {
        User user = resolveUser(principal);
        if (!presenceVisibilityService.canView(user.getId(), targetUserId)) {
            LOG.warn("Rejected presence SUBSCRIBE for {} by {}", targetUserId, principal.getName());
            throw new MessageDeliveryException("Presence not visible");
        }
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new MessageDeliveryException("Unknown user"));
    }
}
