package com.bovae.yac.service;

import com.bovae.yac.model.enums.PresenceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String PRESENCE_KEY_PREFIX = "presence:";
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate stringRedisTemplate;

    public void updatePresence(UUID userId, PresenceStatus status) {
        String key = PRESENCE_KEY_PREFIX + userId;
        stringRedisTemplate.opsForValue().set(key, status.name(), PRESENCE_TTL);
        LOG.debug("Updated presence for user {}: {}", userId, status);
    }

    public PresenceStatus getPresence(UUID userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return PresenceStatus.OFFLINE;
        }
        try {
            return PresenceStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Invalid presence value for user {}: {}", userId, value);
            return PresenceStatus.OFFLINE;
        }
    }

    public void removePresence(UUID userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        stringRedisTemplate.delete(key);
        LOG.debug("Removed presence for user {}", userId);
    }
}
