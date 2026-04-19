package com.bovae.yac.unit;

import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PresenceService}.
 *
 * <p>Validates Correctness Property: CP 9.
 * <p>Requirements: 5.8, 5.9, 5.10.
 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private PresenceService presenceService;

    private User user;
    private UUID userId;
    private String presenceKey;
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        presenceKey = "presence:" + userId;

        user = User.builder()
                .id(userId)
                .email("testuser@test.com")
                .username("testuser")
                .passwordHash("$2a$10$hash")
                .build();

        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    // -----------------------------------------------------------------------
    // recordHeartbeat with active=true results in ONLINE status (CP 9)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 9, Requirement 5.8: recordHeartbeat with active=true
     * stores the heartbeat in Redis and results in ONLINE status.
     */
    @Test
    void recordHeartbeat_activeTrue_resultsInOnlineStatus() {
        // Stub the hash get calls that computeStatus will make after the put
        String nowMillis = String.valueOf(Instant.now().toEpochMilli());
        when(hashOperations.get(presenceKey, "lastHeartbeat")).thenReturn(nowMillis);
        when(hashOperations.get(presenceKey, "active")).thenReturn("true");

        // Stub userRepository for broadcast
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        presenceService.recordHeartbeat(userId, true);

        // Verify heartbeat was stored in Redis
        verify(hashOperations).put(eq(presenceKey), eq("lastHeartbeat"), anyString());
        verify(hashOperations).put(eq(presenceKey), eq("active"), eq("true"));
        verify(stringRedisTemplate).expire(eq(presenceKey), eq(Duration.ofSeconds(90)));

        // Verify computed status is ONLINE
        PresenceStatus status = presenceService.computeStatus(userId);
        assertThat(status).isEqualTo(PresenceStatus.ONLINE);
    }

    // -----------------------------------------------------------------------
    // recordHeartbeat with active=false results in AFK status (CP 9)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 9, Requirement 5.9: recordHeartbeat with active=false
     * stores the heartbeat in Redis and results in AFK status.
     */
    @Test
    void recordHeartbeat_activeFalse_resultsInAfkStatus() {
        // Stub the hash get calls that computeStatus will make after the put
        String nowMillis = String.valueOf(Instant.now().toEpochMilli());
        when(hashOperations.get(presenceKey, "lastHeartbeat")).thenReturn(nowMillis);
        when(hashOperations.get(presenceKey, "active")).thenReturn("false");

        // Stub userRepository for broadcast
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        presenceService.recordHeartbeat(userId, false);

        // Verify heartbeat was stored in Redis
        verify(hashOperations).put(eq(presenceKey), eq("lastHeartbeat"), anyString());
        verify(hashOperations).put(eq(presenceKey), eq("active"), eq("false"));
        verify(stringRedisTemplate).expire(eq(presenceKey), eq(Duration.ofSeconds(90)));

        // Verify computed status is AFK
        PresenceStatus status = presenceService.computeStatus(userId);
        assertThat(status).isEqualTo(PresenceStatus.AFK);
    }

    // -----------------------------------------------------------------------
    // removePresence results in OFFLINE status (CP 9)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 9, Requirement 5.10: removePresence deletes the Redis key
     * and results in OFFLINE status.
     */
    @Test
    void removePresence_resultsInOfflineStatus() {
        // Stub userRepository for broadcast (removePresence calls broadcastIfChanged)
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        presenceService.removePresence(userId);

        // Verify the Redis key was deleted
        verify(stringRedisTemplate).delete(presenceKey);

        // After removal, computeStatus should return OFFLINE (no data in Redis)
        when(hashOperations.get(presenceKey, "lastHeartbeat")).thenReturn(null);

        PresenceStatus status = presenceService.computeStatus(userId);
        assertThat(status).isEqualTo(PresenceStatus.OFFLINE);
    }
}
