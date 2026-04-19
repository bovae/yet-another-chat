package com.bovae.yac.property;

import com.bovae.yac.config.NavbarModelAdvice;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for NavbarModelAdvice — navbarUser model attribute injection.
 *
 * Validates: Requirements 2.6
 */
class NavbarModelAdvicePropertyTest {

    private UserRepository userRepository;
    private NavbarModelAdvice advice;

    @BeforeTry
    void setUp() {
        userRepository = mock(UserRepository.class);
        advice = new NavbarModelAdvice(userRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterTry
    void tearDown() {
        SecurityContextHolder.clearContext();
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

    /**
     * Property 1: NavbarUser model attribute injection
     *
     * For any authenticated HTTP request to a Thymeleaf-rendered page, the model SHALL
     * contain a navbarUser attribute equal to the authenticated User entity.
     *
     * Validates: Requirements 2.6
     */
    @Property(tries = 20)
    void authenticatedRequestShallReturnUser(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username
    ) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .username(username)
                .passwordHash("hashed")
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(email, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        User result = advice.navbarUser();

        assertThat(result)
                .as("Authenticated request should return the user entity")
                .isNotNull()
                .isEqualTo(user);
    }

    /**
     * Property 1: NavbarUser model attribute injection
     *
     * For any anonymous or unauthenticated request, the navbarUser attribute SHALL be null.
     *
     * Validates: Requirements 2.6
     */
    @Property(tries = 20)
    void anonymousRequestShallReturnNull(
            @ForAll("validEmails") String email
    ) {
        AnonymousAuthenticationToken anonAuth = new AnonymousAuthenticationToken(
                "key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonAuth);

        User result = advice.navbarUser();

        assertThat(result)
                .as("Anonymous request should return null")
                .isNull();
    }

    /**
     * Property 1: NavbarUser model attribute injection
     *
     * For any request with null authentication, the navbarUser attribute SHALL be null.
     *
     * Validates: Requirements 2.6
     */
    @Property(tries = 10)
    void nullAuthenticationShallReturnNull() {
        // SecurityContextHolder cleared in @BeforeTry — auth is null
        User result = advice.navbarUser();

        assertThat(result)
                .as("Null authentication should return null")
                .isNull();
    }
}
