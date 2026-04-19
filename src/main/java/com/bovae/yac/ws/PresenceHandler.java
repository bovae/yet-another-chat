package com.bovae.yac.ws;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PresenceHandler {

    private final PresenceService presenceService;
    private final UserRepository userRepository;

    @MessageMapping("/presence.heartbeat")
    public void heartbeat(Map<String, Object> payload, Principal principal) {
        User user = resolveUser(principal);

        boolean active = false;
        Object activeValue = payload.get("active");
        if (activeValue instanceof Boolean b) {
            active = b;
        } else if (activeValue instanceof String s) {
            active = Boolean.parseBoolean(s);
        }

        presenceService.recordHeartbeat(user.getId(), active);

        LOG.debug("Heartbeat from user {}: active={}", user.getUsername(), active);
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: %s".formatted(principal.getName())));
    }
}
