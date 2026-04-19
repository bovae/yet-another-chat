package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for PresenceService status computation.
 *
 * Validates: Requirements 5.1, 5.2, 5.3, 5.6
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class PresencePropertyTest {

    private static final String PRESENCE_KEY_PREFIX = "presence:";

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterTry
    void cleanup() {
        // Clean up Redis presence keys
        Set<String> keys = stringRedisTemplate.keys(PRESENCE_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        userRepository.deleteAll();
    }

    @Provide
    Arbitrary<String> validEmails() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(12)
                .map(local -> local.toLowerCase() + "@example.com");
    }

    @Provide
    Arbitrary<String> validUsernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> validPasswords() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(8)
                .ofMaxLength(30);
    }

    // Feature: online-chat-server, Property 9: Presence status computation
    /**
     * Validates: Requirements 5.1, 5.2, 5.3, 5.6
     *
     * For any set of heartbeat records for a User, the computed presence status SHALL be:
     * - ONLINE if at least one tab sent an active heartbeat within the last heartbeat interval
     * - AFK if all tabs have been inactive for more than 1 minute but heartbeats are still arriving
     * - OFFLINE if no heartbeats have been received within the timeout window
     *
     * Focus on deterministic cases:
     * - Active heartbeat → ONLINE
     * - Inactive heartbeat → AFK (heartbeat arriving but not active)
     * - No Redis key → OFFLINE
     */
    @Property(tries = 10)
    void presenceStatusComputation(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password
    ) {
        // Setup: register user with unique identifiers
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userDto = userService.register(email + suffix, username + suffix, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();
        UUID userId = user.getId();

        // Case 1: No heartbeat data in Redis → status SHALL be OFFLINE
        String key = PRESENCE_KEY_PREFIX + userId;
        stringRedisTemplate.delete(key);
        PresenceStatus statusNoHeartbeat = presenceService.computeStatus(userId);
        assertThat(statusNoHeartbeat)
                .as("Status with no heartbeat data should be OFFLINE")
                .isEqualTo(PresenceStatus.OFFLINE);

        // Case 2: Active heartbeat just sent → status SHALL be ONLINE
        presenceService.recordHeartbeat(userId, true);
        PresenceStatus statusAfterActive = presenceService.computeStatus(userId);
        assertThat(statusAfterActive)
                .as("Status after active heartbeat should be ONLINE")
                .isEqualTo(PresenceStatus.ONLINE);

        // Case 3: Inactive heartbeat just sent → status SHALL be AFK
        // (heartbeat arriving but tab not active)
        presenceService.recordHeartbeat(userId, false);
        PresenceStatus statusAfterInactive = presenceService.computeStatus(userId);
        assertThat(statusAfterInactive)
                .as("Status after inactive heartbeat should be AFK")
                .isEqualTo(PresenceStatus.AFK);

        // Case 4: Remove Redis key (simulates all tabs closed, TTL expired) → OFFLINE
        stringRedisTemplate.delete(key);
        PresenceStatus statusAfterRemoval = presenceService.computeStatus(userId);
        assertThat(statusAfterRemoval)
                .as("Status after Redis key removal should be OFFLINE")
                .isEqualTo(PresenceStatus.OFFLINE);

        // Case 5: Active heartbeat restores ONLINE after being OFFLINE
        presenceService.recordHeartbeat(userId, true);
        PresenceStatus statusRestored = presenceService.computeStatus(userId);
        assertThat(statusRestored)
                .as("Status should return to ONLINE after active heartbeat")
                .isEqualTo(PresenceStatus.ONLINE);
    }
}
