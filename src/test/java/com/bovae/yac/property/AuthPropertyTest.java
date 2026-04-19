package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.AuthService;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for AuthService authentication and session management.
 *
 * Validates: Requirements 2.1, 2.2, 2.4, 2.5, 2.6
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class AuthPropertyTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @AfterTry
    void cleanup() {
        // Clean up sessions for all users before deleting users
        for (User user : userRepository.findAll()) {
            Map<String, ? extends Session> sessions =
                    sessionRepository.findByPrincipalName(user.getEmail());
            for (String sessionId : sessions.keySet()) {
                sessionRepository.deleteById(sessionId);
            }
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
                .ascii()
                .ofMinLength(8)
                .ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> wrongPasswords() {
        return Arbitraries.strings()
                .ascii()
                .ofMinLength(8)
                .ofMaxLength(30)
                .map(p -> p + "WRONG");
    }

    @Provide
    Arbitrary<Integer> sessionCounts() {
        return Arbitraries.integers().between(2, 5);
    }

    // Feature: online-chat-server, Property 4: Authentication round-trip
    /**
     * Validates: Requirements 2.1, 2.2
     *
     * For any registered User with known password, login with correct email+password
     * SHALL succeed (loadUserByUsername returns UserDetails, password matches).
     * Login with any incorrect password SHALL fail.
     */
    @Property(tries = 100)
    void authenticationRoundTrip(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("wrongPasswords") String wrongPassword
    ) {
        // Register user with known password
        userService.register(email, username, password);

        // Load user details via AuthService (Spring Security UserDetailsService)
        UserDetails userDetails = authService.loadUserByUsername(email);

        // Correct password SHALL match
        assertThat(passwordEncoder.matches(password, userDetails.getPassword()))
                .as("Correct password should authenticate successfully")
                .isTrue();

        // Wrong password SHALL NOT match (ensure it's actually different)
        if (!wrongPassword.equals(password)) {
            assertThat(passwordEncoder.matches(wrongPassword, userDetails.getPassword()))
                    .as("Incorrect password should fail authentication")
                    .isFalse();
        }
    }

    // Feature: online-chat-server, Property 5: Session isolation
    /**
     * Validates: Requirements 2.4, 2.5, 2.6
     *
     * For any User with N active sessions (N >= 2), terminating one specific session
     * SHALL invalidate only that session, leaving remaining N-1 sessions valid and listed.
     */
    @SuppressWarnings("unchecked")
    @Property(tries = 20)
    void sessionIsolation(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("sessionCounts") int sessionCount
    ) {
        // Register user
        userService.register(email, username, password);

        // Create N sessions programmatically via SessionRepository
        List<String> sessionIds = new ArrayList<>();
        FindByIndexNameSessionRepository<Session> repo =
                (FindByIndexNameSessionRepository<Session>) sessionRepository;

        for (int i = 0; i < sessionCount; i++) {
            Session session = repo.createSession();
            session.setAttribute(
                    FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                    email
            );
            repo.save(session);
            sessionIds.add(session.getId());
        }

        // Verify all N sessions are listed
        List<AuthService.SessionInfo> beforeList = authService.listSessions(email);
        assertThat(beforeList).hasSize(sessionCount);

        // Pick the first session to terminate
        String terminatedSessionId = sessionIds.get(0);
        authService.terminateSession(terminatedSessionId);

        // Verify only the terminated session is gone
        List<AuthService.SessionInfo> afterList = authService.listSessions(email);
        assertThat(afterList).hasSize(sessionCount - 1);

        // The terminated session should not be in the list
        List<String> remainingIds = afterList.stream()
                .map(AuthService.SessionInfo::sessionId)
                .toList();
        assertThat(remainingIds).doesNotContain(terminatedSessionId);

        // All other sessions should still be present
        for (int i = 1; i < sessionIds.size(); i++) {
            assertThat(remainingIds).contains(sessionIds.get(i));
        }
    }
}
