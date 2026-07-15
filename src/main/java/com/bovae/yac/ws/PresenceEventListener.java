package com.bovae.yac.ws;

import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Removes a user's session from presence the moment its WebSocket disconnects, so status
 * becomes OFFLINE promptly instead of lingering until TTL expiry (R1-39, R1-63).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceEventListener {

    private final PresenceService presenceService;
    private final UserRepository userRepository;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (user == null) {
            return;
        }
        userRepository
                .findByEmail(user.getName())
                .ifPresent(u -> presenceService.removeSession(u.getId(), event.getSessionId()));
    }
}
