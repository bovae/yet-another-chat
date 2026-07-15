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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
}
