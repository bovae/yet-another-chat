package com.bovae.yac.ws;

import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.ChatMessageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.send")
    public void sendMessage(@Valid ChatMessageRequest message, Principal principal) {
        LOG.debug("Message from {} to room {}", principal.getName(), message.roomId());

        // TODO: validate room exists, sender is member, sender not banned

        var response = new ChatMessageResponse(
                principal.getName(),
                message.content(),
                message.roomId(),
                Instant.now()
        );
        messagingTemplate.convertAndSend("/topic/room/" + message.roomId(), response);
    }

    @MessageMapping("/presence.heartbeat")
    public void heartbeat(Map<String, Object> payload, Principal principal) {
        LOG.debug("Heartbeat from {}", principal.getName());
        // Skeleton — presence update logic during feature development
    }
}
