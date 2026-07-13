package com.bovae.yac.service;

import com.bovae.yac.model.dto.PresenceUpdate;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-device presence (design D10). Each user's presence is a Redis hash keyed by STOMP
 * session id, so a status is the aggregate of all live sessions rather than last-writer-wins
 * (R1-38). A session is ONLINE while active or active within the last idle window, AFK once
 * idle past it, and OFFLINE when its heartbeats stop. Explicit disconnect events remove a
 * session immediately instead of waiting for TTL (R1-39, R1-63).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String PRESENCE_KEY_PREFIX = "presence:";
    private static final Duration SESSION_TTL = Duration.ofSeconds(90);
    private static final long IDLE_THRESHOLD_MS = Duration.ofSeconds(60).toMillis();

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    /** Last broadcast status per user, so transitions fire exactly once (R1-61). */
    private final Map<UUID, PresenceStatus> lastKnownStatus = new ConcurrentHashMap<>();

    /** Records a heartbeat for one device/session (R1-38). */
    public void recordHeartbeat(UUID userId, String sessionId, boolean active) {
        String key = PRESENCE_KEY_PREFIX + userId;
        long now = Instant.now().toEpochMilli();

        Object existing = stringRedisTemplate.opsForHash().get(key, sessionId);
        long lastActive = active ? now : parseLastActive(existing, now);

        stringRedisTemplate.opsForHash().put(key, sessionId, now + ":" + lastActive);
        stringRedisTemplate.expire(key, SESSION_TTL);

        broadcastIfChanged(userId, computeStatus(userId));
        LOG.debug("Heartbeat: user={}, session={}, active={}", userId, sessionId, active);
    }

    /** Removes one session on disconnect; broadcasts OFFLINE when it was the last one (R1-39, R1-63). */
    public void removeSession(UUID userId, String sessionId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        stringRedisTemplate.opsForHash().delete(key, sessionId);

        Long remaining = stringRedisTemplate.opsForHash().size(key);
        if (remaining == null || remaining == 0) {
            stringRedisTemplate.delete(key);
            broadcastIfChanged(userId, PresenceStatus.OFFLINE);
        } else {
            broadcastIfChanged(userId, computeStatus(userId));
        }
        LOG.debug("Removed presence session: user={}, session={}, remaining={}", userId, sessionId, remaining);
    }

    public PresenceStatus getUserStatus(UUID userId) {
        return computeStatus(userId);
    }

    public PresenceStatus computeStatus(UUID userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        Map<Object, Object> sessions = stringRedisTemplate.opsForHash().entries(key);
        return aggregate(sessions.values(), Instant.now().toEpochMilli());
    }

    /**
     * Periodic sweep using SCAN (never KEYS, R1-59): prunes stale session fields, deletes empty
     * keys, and re-broadcasts any resulting transitions.
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupStalePresence() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(PRESENCE_KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }

        long now = Instant.now().toEpochMilli();
        for (String key : keys) {
            UUID userId = parseUserId(key);
            if (userId == null) {
                continue;
            }
            pruneStaleSessions(key, now);

            Long remaining = stringRedisTemplate.opsForHash().size(key);
            if (remaining == null || remaining == 0) {
                stringRedisTemplate.delete(key);
                broadcastIfChanged(userId, PresenceStatus.OFFLINE);
            } else {
                broadcastIfChanged(userId, computeStatus(userId));
            }
        }
    }

    private void pruneStaleSessions(String key, long now) {
        Map<Object, Object> sessions = stringRedisTemplate.opsForHash().entries(key);
        for (Map.Entry<Object, Object> entry : sessions.entrySet()) {
            if (isExpired(entry.getValue(), now)) {
                stringRedisTemplate.opsForHash().delete(key, entry.getKey());
            }
        }
    }

    private PresenceStatus aggregate(Iterable<Object> sessionValues, long now) {
        boolean anyLive = false;
        boolean anyOnline = false;
        for (Object value : sessionValues) {
            if (isExpired(value, now)) {
                continue;
            }
            anyLive = true;
            long lastActive = parseLastActive(value, 0L);
            if (now - lastActive <= IDLE_THRESHOLD_MS) {
                anyOnline = true;
            }
        }
        if (anyOnline) {
            return PresenceStatus.ONLINE;
        }
        return anyLive ? PresenceStatus.AFK : PresenceStatus.OFFLINE;
    }

    // Transition detection is a single atomic map operation so a heartbeat and the sweep
    // cannot double-broadcast or drop a change (R1-61).
    private void broadcastIfChanged(UUID userId, PresenceStatus newStatus) {
        lastKnownStatus.compute(userId, (id, previous) -> {
            if (previous != newStatus) {
                broadcastPresenceUpdate(userId, newStatus);
                LOG.info("Presence changed for user {}: {} -> {}", userId, previous, newStatus);
            }
            // Drop the mapping once OFFLINE so the map does not grow without bound.
            return newStatus == PresenceStatus.OFFLINE ? null : newStatus;
        });
    }

    private void broadcastPresenceUpdate(UUID userId, PresenceStatus status) {
        String username = userRepository.findById(userId)
                .map(user -> user.getUsername())
                .orElse("unknown");
        PresenceUpdate update = new PresenceUpdate(userId, username, status, Instant.now());
        messagingTemplate.convertAndSend("/topic/presence." + userId, update);
    }

    private boolean isExpired(Object value, long now) {
        return now - parseLastBeat(value) > SESSION_TTL.toMillis();
    }

    private long parseLastBeat(Object value) {
        String[] parts = String.valueOf(value).split(":");
        return parseLong(parts.length > 0 ? parts[0] : null, 0L);
    }

    private long parseLastActive(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        String[] parts = String.valueOf(value).split(":");
        return parseLong(parts.length > 1 ? parts[1] : null, fallback);
    }

    private long parseLong(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private UUID parseUserId(String key) {
        try {
            return UUID.fromString(key.substring(PRESENCE_KEY_PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            LOG.warn("Invalid presence key format: {}", key);
            return null;
        }
    }
}
