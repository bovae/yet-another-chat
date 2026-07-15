package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bovae.yac.model.dto.PresenceUpdate;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for the per-session presence model (R1-38 … R1-63): a status is the aggregate over
 * live sessions — ONLINE if any is active within the idle window, AFK if only idle, OFFLINE when none.
 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    private static final long TTL_MS = Duration.ofSeconds(90).toMillis();

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private Cursor<String> cursor;

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

    // --- recordHeartbeat ---

    @Test
    void recordHeartbeat_storesSessionAndRefreshesTtl() {
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of());

        presenceService.recordHeartbeat(userId, "s1", true);

        verify(hashOperations).put(eq(presenceKey), eq("s1"), anyString());
        verify(stringRedisTemplate).expire(eq(presenceKey), eq(Duration.ofSeconds(90)));
    }

    @Test
    void recordHeartbeat_broadcastsOnce_whenRepeatedHeartbeatKeepsSameStatus() {
        long now = Instant.now().toEpochMilli();
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", now + ":" + now));

        presenceService.recordHeartbeat(userId, "s1", true);
        presenceService.recordHeartbeat(userId, "s1", true);

        // First heartbeat transitions null -> ONLINE and broadcasts; the second keeps ONLINE
        // so broadcastIfChanged must NOT fire again (R1-61).
        ArgumentCaptor<PresenceUpdate> captor = ArgumentCaptor.forClass(PresenceUpdate.class);
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/presence." + userId), captor.capture());
        // The broadcast carries the resolved username and ONLINE status, not a placeholder.
        assertThat(captor.getValue().username()).isEqualTo("testuser");
        assertThat(captor.getValue().status()).isEqualTo(PresenceStatus.ONLINE);
    }

    @ParameterizedTest(name = "active={0}, stored={1}")
    @CsvSource({
        "true, '100:200'",
        "false, '12345'",
        "false, '100:notANumber'",
    })
    void recordHeartbeat_storesNowAsLastActive_whenActiveOrStoredLastActiveUnusable(boolean active, String stored) {
        // On active=true lastActive must be `now`; on active=false with an unusable stored last-active
        // (missing or non-numeric part) it must fall back to `now`, never 0. In every case the stored
        // "beat:lastActive" pair has equal halves.
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of());
        when(hashOperations.get(presenceKey, "s1")).thenReturn(stored);

        presenceService.recordHeartbeat(userId, "s1", active);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(presenceKey), eq("s1"), captor.capture());
        String[] parts = captor.getValue().split(":");
        assertThat(parts).hasSize(2);
        assertThat(parts[1]).isEqualTo(parts[0]);
    }

    // --- computeStatus ---

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
    void computeStatus_skipsSession_whenHeartbeatOlderThanTtl() {
        long now = Instant.now().toEpochMilli();
        // Last beat well past the 90s TTL — the session is stale and must be ignored.
        String stale = (now - TTL_MS - 10_000) + ":" + (now - TTL_MS - 10_000);
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", stale));

        assertThat(presenceService.computeStatus(userId)).isEqualTo(PresenceStatus.OFFLINE);
    }

    @Test
    void computeStatus_treatsSessionAsStale_whenLastBeatIsNotNumeric() {
        long now = Instant.now().toEpochMilli();
        // A non-numeric last-beat parses to 0, which is older than any TTL window, so it is stale.
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", "notALong:" + now));

        assertThat(presenceService.computeStatus(userId)).isEqualTo(PresenceStatus.OFFLINE);
    }

    @Test
    void computeStatus_treatsSessionAsIdle_whenValueHasNoLastActivePart() {
        long now = Instant.now().toEpochMilli();
        // No colon: a live beat but a missing last-active field defaults to 0 (idle) -> AFK.
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", String.valueOf(now)));

        assertThat(presenceService.computeStatus(userId)).isEqualTo(PresenceStatus.AFK);
    }

    @Test
    void computeStatus_treatsSessionAsStale_whenValueIsDelimiterOnly() {
        // ":" splits to an empty array; last beat defaults to 0, so the session is stale.
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", ":"));

        assertThat(presenceService.computeStatus(userId)).isEqualTo(PresenceStatus.OFFLINE);
    }

    // --- removeSession ---

    @Test
    void removeSession_lastSession_deletesKey() {
        when(hashOperations.size(presenceKey)).thenReturn(0L);

        presenceService.removeSession(userId, "s1");

        verify(hashOperations).delete(presenceKey, "s1");
        verify(stringRedisTemplate).delete(presenceKey);
    }

    @Test
    void removeSession_broadcastsOffline_whenSizeIsNull() {
        when(hashOperations.size(presenceKey)).thenReturn(null);

        presenceService.removeSession(userId, "s1");

        verify(hashOperations).delete(presenceKey, "s1");
        verify(stringRedisTemplate).delete(presenceKey);
        verify(messagingTemplate).convertAndSend(eq("/topic/presence." + userId), any(PresenceUpdate.class));
    }

    @Test
    void removeSession_broadcastsComputedStatus_whenOtherSessionsRemain() {
        long now = Instant.now().toEpochMilli();
        when(hashOperations.size(presenceKey)).thenReturn(1L);
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s2", now + ":" + now));

        presenceService.removeSession(userId, "s1");

        verify(hashOperations).delete(presenceKey, "s1");
        verify(stringRedisTemplate, never()).delete(presenceKey);
        verify(messagingTemplate).convertAndSend(eq("/topic/presence." + userId), any(PresenceUpdate.class));
    }

    // --- cleanupStalePresence ---

    @Test
    void cleanupStalePresence_prunesStaleFieldsAndBroadcastsOffline_whenKeyBecomesEmpty() {
        long now = Instant.now().toEpochMilli();
        stubScanReturns(presenceKey);
        String stale = (now - TTL_MS - 10_000) + ":" + (now - TTL_MS - 10_000);
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", stale));
        when(hashOperations.size(presenceKey)).thenReturn(0L);

        presenceService.cleanupStalePresence();

        verify(hashOperations).delete(presenceKey, "s1");
        verify(stringRedisTemplate).delete(presenceKey);
        verify(messagingTemplate).convertAndSend(eq("/topic/presence." + userId), any(PresenceUpdate.class));
    }

    @Test
    void cleanupStalePresence_broadcastsOffline_whenSizeIsNullAfterPrune() {
        stubScanReturns(presenceKey);
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of());
        when(hashOperations.size(presenceKey)).thenReturn(null);

        presenceService.cleanupStalePresence();

        verify(stringRedisTemplate).delete(presenceKey);
        verify(messagingTemplate).convertAndSend(eq("/topic/presence." + userId), any(PresenceUpdate.class));
    }

    @Test
    void cleanupStalePresence_broadcastsComputedStatus_whenSessionsRemain() {
        long now = Instant.now().toEpochMilli();
        stubScanReturns(presenceKey);
        when(hashOperations.entries(presenceKey)).thenReturn(Map.of("s1", now + ":" + now));
        when(hashOperations.size(presenceKey)).thenReturn(1L);

        presenceService.cleanupStalePresence();

        verify(stringRedisTemplate, never()).delete(presenceKey);
        verify(messagingTemplate).convertAndSend(eq("/topic/presence." + userId), any(PresenceUpdate.class));
    }

    @Test
    void cleanupStalePresence_skipsKey_whenKeySuffixIsNotAUuid() {
        stubScanReturns("presence:not-a-uuid");

        presenceService.cleanupStalePresence();

        verify(stringRedisTemplate, never()).delete(anyString());
        verifyNoInteractions(messagingTemplate);
    }

    private void stubScanReturns(String... keys) {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        doAnswer(invocation -> {
                    Consumer<String> action = invocation.getArgument(0);
                    for (String key : keys) {
                        action.accept(key);
                    }
                    return null;
                })
                .when(cursor)
                .forEachRemaining(any());
    }
}
