package com.bovae.yac.service;

import com.bovae.yac.model.dto.PresenceUpdate;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String PRESENCE_KEY_PREFIX = "presence:";
    private static final String FIELD_LAST_HEARTBEAT = "lastHeartbeat";
    private static final String FIELD_ACTIVE = "active";
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(90);
    private static final long HEARTBEAT_INTERVAL_MS = Duration.ofSeconds(30).toMillis();
    private static final long AFK_THRESHOLD_MS = Duration.ofSeconds(60).toMillis();

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    /**
     * Tracks the last known status per user so we only broadcast on changes.
     */
    private final Map<UUID, PresenceStatus> lastKnownStatus = new ConcurrentHashMap<>();

    public void recordHeartbeat(UUID userId, boolean active) {
        String key = PRESENCE_KEY_PREFIX + userId;

        stringRedisTemplate.opsForHash().put(key, FIELD_LAST_HEARTBEAT,
                String.valueOf(Instant.now().toEpochMilli()));
        stringRedisTemplate.opsForHash().put(key, FIELD_ACTIVE, String.valueOf(active));
        stringRedisTemplate.expire(key, PRESENCE_TTL);

        PresenceStatus newStatus = computeStatus(userId);
        broadcastIfChanged(userId, newStatus);

        LOG.debug("Recorded heartbeat for user {}: active={}, status={}", userId, active, newStatus);
    }

    public PresenceStatus computeStatus(UUID userId) {
        String key = PRESENCE_KEY_PREFIX + userId;

        String lastHeartbeatStr = (String) stringRedisTemplate.opsForHash()
                .get(key, FIELD_LAST_HEARTBEAT);
        String activeStr = (String) stringRedisTemplate.opsForHash()
                .get(key, FIELD_ACTIVE);

        if (lastHeartbeatStr == null) {
            return PresenceStatus.OFFLINE;
        }

        long lastHeartbeat;
        try {
            lastHeartbeat = Long.parseLong(lastHeartbeatStr);
        } catch (NumberFormatException ex) {
            LOG.warn("Invalid lastHeartbeat value for user {}: {}", userId, lastHeartbeatStr);
            return PresenceStatus.OFFLINE;
        }

        long now = Instant.now().toEpochMilli();
        long elapsed = now - lastHeartbeat;

        if (elapsed > PRESENCE_TTL.toMillis()) {
            return PresenceStatus.OFFLINE;
        }

        boolean active = Boolean.parseBoolean(activeStr);

        if (active && elapsed <= HEARTBEAT_INTERVAL_MS) {
            return PresenceStatus.ONLINE;
        }

        if (!active && elapsed > AFK_THRESHOLD_MS) {
            return PresenceStatus.AFK;
        }

        // Active heartbeat but older than interval — still consider ONLINE
        // as long as within TTL and was active
        if (active) {
            return PresenceStatus.ONLINE;
        }

        // Inactive but within AFK threshold — transitional, treat as AFK
        return PresenceStatus.AFK;
    }

    public PresenceStatus getUserStatus(UUID userId) {
        return computeStatus(userId);
    }

    @Scheduled(fixedRate = 30000)
    public void cleanupStalePresence() {
        Set<String> keys = stringRedisTemplate.keys(PRESENCE_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            String userIdStr = key.substring(PRESENCE_KEY_PREFIX.length());
            try {
                UUID userId = UUID.fromString(userIdStr);
                PresenceStatus status = computeStatus(userId);
                broadcastIfChanged(userId, status);
            } catch (IllegalArgumentException ex) {
                LOG.warn("Invalid presence key format: {}", key);
            }
        }

        // Broadcast OFFLINE for tracked users whose Redis keys have expired
        lastKnownStatus.entrySet().removeIf(entry -> {
            UUID userId = entry.getKey();
            String key = PRESENCE_KEY_PREFIX + userId;
            Boolean exists = stringRedisTemplate.hasKey(key);
            if (exists != null && !exists && entry.getValue() != PresenceStatus.OFFLINE) {
                broadcastPresenceUpdate(userId, PresenceStatus.OFFLINE);
                LOG.debug("Cleaned up expired presence for user {}", userId);
                return true;
            }
            return false;
        });
    }

    public void removePresence(UUID userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        stringRedisTemplate.delete(key);
        broadcastIfChanged(userId, PresenceStatus.OFFLINE);
        lastKnownStatus.remove(userId);
        LOG.debug("Removed presence for user {}", userId);
    }

    private void broadcastIfChanged(UUID userId, PresenceStatus newStatus) {
        PresenceStatus previous = lastKnownStatus.get(userId);
        if (previous == newStatus) {
            return;
        }

        lastKnownStatus.put(userId, newStatus);
        broadcastPresenceUpdate(userId, newStatus);

        LOG.info("Presence changed for user {}: {} -> {}", userId, previous, newStatus);
    }

    private void broadcastPresenceUpdate(UUID userId, PresenceStatus status) {
        String username = userRepository.findById(userId)
                .map(user -> user.getUsername())
                .orElse("unknown");

        PresenceUpdate update = new PresenceUpdate(userId, username, status, Instant.now());
        messagingTemplate.convertAndSend("/topic/presence." + userId, update);
    }
}
