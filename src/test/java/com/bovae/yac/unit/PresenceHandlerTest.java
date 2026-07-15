package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import com.bovae.yac.ws.PresenceHandler;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PresenceHandler}.
 *
 * <p>Covers the {@code active} flag coercion (Boolean, String, or default false) and the
 * unknown-user guard on the STOMP {@code /presence.heartbeat} mapping.
 */
@ExtendWith(MockitoExtension.class)
class PresenceHandlerTest {

    private static final String USER_EMAIL = "testuser@test.com";
    private static final String SESSION_ID = "session-1";

    @Mock
    private PresenceService presenceService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PresenceHandler presenceHandler;

    private Principal principal;
    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        principal = () -> USER_EMAIL;
        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId)
                .email(USER_EMAIL)
                .username("testuser")
                .passwordHash("$2a$10$hash")
                .build();
    }

    static Stream<Arguments> activeFlagCases() {
        return Stream.of(
                Arguments.of(Map.of("active", true), true),
                Arguments.of(Map.of("active", false), false),
                Arguments.of(Map.of("active", "true"), true),
                Arguments.of(Map.of("active", "false"), false),
                Arguments.of(Map.of("active", 1), false),
                Arguments.of(Map.of(), false));
    }

    @ParameterizedTest(name = "payload={0} -> active={1}")
    @MethodSource("activeFlagCases")
    void heartbeat_recordsCoercedActiveFlag_whenPayloadVaries(Map<String, Object> payload, boolean expectedActive) {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        presenceHandler.heartbeat(payload, SESSION_ID, principal);

        verify(presenceService).recordHeartbeat(userId, SESSION_ID, expectedActive);
    }

    @Test
    void heartbeat_throwsResourceNotFound_whenUserUnknown() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> presenceHandler.heartbeat(Map.of("active", true), SESSION_ID, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");

        verifyNoInteractions(presenceService);
    }
}
