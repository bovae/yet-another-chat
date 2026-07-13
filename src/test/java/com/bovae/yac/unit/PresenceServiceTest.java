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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-session presence model (R1-38 … R1-63): a status is the aggregate over
 * live sessions — ONLINE if any is active within the idle window, AFK if only idle, OFFLINE when none.
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

        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void recordHeartbeat_storesSessionAndRefreshesTtl() {
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of());

        presenceService.recordHeartbeat(userId, "s1", true);

        verify(hashOperations).put(eq(presenceKey), eq("s1"), anyString());
        verify(stringRedisTemplate).expire(eq(presenceKey), eq(Duration.ofSeconds(90)));
    }

    @Test
    void computeStatus_recentActiveSession_isOnline() {
        long now = Instant.now().toEpochMilli();
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", now + ":" + now));

        assertThat(presenceService.computeStatus(userId)).isEqualTo(PresenceStatus.ONLINE);
    }

    @Test
    void computeStatus_liveButIdlePastThreshold_isAfk() {
        long now = Instant.now().toEpochMilli();
        // Live session (recent heartbeat) but last active > 60s ago.
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", now + ":" + (now - 70_000)));

        assertThat(presenceService.computeStatus(userId)).isEqualTo(PresenceStatus.AFK);
    }

    @Test
    void computeStatus_noSessions_isOffline() {
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of());

        assertThat(presenceService.computeStatus(userId)).isEqualTo(PresenceStatus.OFFLINE);
    }

    @Test
    void removeSession_lastSession_deletesKey() {
        when(hashOperations.size(presenceKey)).thenReturn(0L);

        presenceService.removeSession(userId, "s1");

        verify(hashOperations).delete(presenceKey, "s1");
        verify(stringRedisTemplate).delete(presenceKey);
    }
}
