package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.AuthService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Unit tests for {@link AuthService}.
 *
 * <p>Validates Correctness Properties: CP 4, CP 5.
 * <p>Requirements: 2.6, 2.7, 2.8.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @InjectMocks
    private AuthService authService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("$2a$10$hashedpassword")
                .build();
    }

    /**
     * Validates CP 4: loadUserByUsername with a valid email returns UserDetails
     * with matching credentials (email as username, password hash, ROLE_USER authority).
     */
    @Test
    void loadUserByUsername_withValidEmail_returnsUserDetailsWithMatchingCredentials() {
        when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));

        UserDetails userDetails = authService.loadUserByUsername(existingUser.getEmail());

        assertThat(userDetails.getUsername()).isEqualTo(existingUser.getEmail());
        assertThat(userDetails.getPassword()).isEqualTo(existingUser.getPasswordHash());
        assertThat(userDetails.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    /**
     * Validates CP 4: loadUserByUsername with an unknown email throws
     * UsernameNotFoundException.
     */
    @Test
    void loadUserByUsername_withUnknownEmail_throwsUsernameNotFoundException() {
        String unknownEmail = "nobody@test.com";
        when(userRepository.findByEmail(unknownEmail)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.loadUserByUsername(unknownEmail))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining(unknownEmail);
    }

    /**
     * Validates R1-11: terminateSession deletes a session the caller owns.
     */
    @Test
    void terminateSession_ownedSession_deletesIt() {
        String sessionId = "session-abc-123";
        Session session = mock(Session.class);
        doReturn(Map.of(sessionId, session)).when(sessionRepository).findByPrincipalName(existingUser.getEmail());

        authService.terminateSession(sessionId, existingUser.getEmail());

        verify(sessionRepository).deleteById(sessionId);
    }

    /**
     * Validates R1-11: terminating another user's session is rejected with 404 and no delete.
     */
    @Test
    void terminateSession_notOwned_throwsNotFoundAndDoesNotDelete() {
        doReturn(Map.of()).when(sessionRepository).findByPrincipalName(existingUser.getEmail());

        assertThatThrownBy(() -> authService.terminateSession("someone-elses-session", existingUser.getEmail()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(sessionRepository, never()).deleteById(any());
    }

    // --- listSessions IP address extraction (extractIpAddress branches) ---

    /**
     * Validates listSessions surfaces the captured CLIENT_IP attribute as the session's
     * IP address (taking precedence over any derived address), along with user agent and
     * timestamps.
     */
    @Test
    void listSessions_capturedClientIpPresent_returnsCapturedIp() {
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant accessedAt = Instant.parse("2026-01-01T11:00:00Z");
        Session session = mock(Session.class);
        when(session.<String>getAttribute("CLIENT_IP")).thenReturn("captured-client-ip");
        when(session.<String>getAttribute("USER_AGENT")).thenReturn("Firefox");
        when(session.getCreationTime()).thenReturn(createdAt);
        when(session.getLastAccessedTime()).thenReturn(accessedAt);
        doReturn(Map.of("sess-1", session)).when(sessionRepository).findByPrincipalName(existingUser.getEmail());

        List<AuthService.SessionInfo> sessions = authService.listSessions(existingUser.getEmail());

        assertThat(sessions).hasSize(1);
        AuthService.SessionInfo info = sessions.get(0);
        assertThat(info.sessionId()).isEqualTo("sess-1");
        assertThat(info.ipAddress()).isEqualTo("captured-client-ip");
        assertThat(info.userAgent()).isEqualTo("Firefox");
        assertThat(info.creationTime()).isEqualTo(createdAt);
        assertThat(info.lastAccessedTime()).isEqualTo(accessedAt);
    }

    /**
     * Validates listSessions yields a null IP when no CLIENT_IP is captured and the
     * security context holds no authentication to derive an address from.
     */
    @Test
    void listSessions_securityContextWithoutAuthentication_returnsNullIp() {
        Session session = mock(Session.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(session.<String>getAttribute("CLIENT_IP")).thenReturn(null);
        when(session.<String>getAttribute("USER_AGENT")).thenReturn(null);
        when(session.<SecurityContext>getAttribute("SPRING_SECURITY_CONTEXT")).thenReturn(securityContext);
        doReturn(Map.of("sess-1", session)).when(sessionRepository).findByPrincipalName(existingUser.getEmail());

        List<AuthService.SessionInfo> sessions = authService.listSessions(existingUser.getEmail());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).ipAddress()).isNull();
    }

    /**
     * Validates listSessions derives the IP from the authentication's
     * WebAuthenticationDetails remote address when no CLIENT_IP was captured.
     */
    @Test
    void listSessions_webAuthenticationDetailsPresent_returnsRemoteAddress() {
        Session session = mock(Session.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        WebAuthenticationDetails webDetails = mock(WebAuthenticationDetails.class);
        when(session.<String>getAttribute("CLIENT_IP")).thenReturn(null);
        when(session.<String>getAttribute("USER_AGENT")).thenReturn(null);
        when(session.<SecurityContext>getAttribute("SPRING_SECURITY_CONTEXT")).thenReturn(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getDetails()).thenReturn(webDetails);
        when(webDetails.getRemoteAddress()).thenReturn("details-remote-addr");
        doReturn(Map.of("sess-1", session)).when(sessionRepository).findByPrincipalName(existingUser.getEmail());

        List<AuthService.SessionInfo> sessions = authService.listSessions(existingUser.getEmail());

        assertThat(sessions.get(0).ipAddress()).isEqualTo("details-remote-addr");
    }

    /**
     * Validates listSessions yields a null IP when the authentication details are not a
     * WebAuthenticationDetails, so no remote address can be derived.
     */
    @Test
    void listSessions_authenticationDetailsNotWebAuthenticationDetails_returnsNullIp() {
        Session session = mock(Session.class);
        SecurityContext securityContext = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(session.<String>getAttribute("CLIENT_IP")).thenReturn(null);
        when(session.<String>getAttribute("USER_AGENT")).thenReturn(null);
        when(session.<SecurityContext>getAttribute("SPRING_SECURITY_CONTEXT")).thenReturn(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getDetails()).thenReturn("plain-details");
        doReturn(Map.of("sess-1", session)).when(sessionRepository).findByPrincipalName(existingUser.getEmail());

        List<AuthService.SessionInfo> sessions = authService.listSessions(existingUser.getEmail());

        assertThat(sessions.get(0).ipAddress()).isNull();
    }
}
