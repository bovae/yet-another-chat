package com.bovae.yac.ws;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.RoomEvent;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class TypingHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;

    @MessageMapping("/typing")
    public void typing(Map<String, Object> payload, Principal principal) {
        User user = resolveUser(principal);

        Object roomIdValue = payload.get("roomId");
        if (roomIdValue == null) {
            roomIdValue = payload.get("room_id");
        }
        if (roomIdValue == null) {
            LOG.warn("Typing indicator missing roomId from user {}", user.getUsername());
            return;
        }

        UUID roomId;
        try {
            roomId = UUID.fromString(roomIdValue.toString());
        } catch (IllegalArgumentException ex) {
            LOG.warn("Invalid roomId in typing indicator from user {}: {}", user.getUsername(), roomIdValue);
            return;
        }

        // Only members may inject typing events into a room (R1-40).
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null || !roomMemberRepository.existsByRoomAndUser(room, user)) {
            LOG.debug("Ignoring typing event for room {} from non-member {}", roomId, user.getUsername());
            return;
        }

        RoomEvent event = new RoomEvent("TYPING", roomId, user.getId(), user.getUsername());
        messagingTemplate.convertAndSend("/topic/room." + roomId + ".events", event);

        LOG.debug("Typing indicator broadcast for room {} from user {}", roomId, user.getUsername());
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: %s".formatted(principal.getName())));
    }
}
