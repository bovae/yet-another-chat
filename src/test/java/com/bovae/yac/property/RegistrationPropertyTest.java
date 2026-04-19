package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
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
import org.springframework.security.crypto.bcrypt.BCrypt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for UserService registration, password hashing, and username immutability.
 *
 * Validates: Requirements 1.2, 1.3, 1.4, 1.5
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class RegistrationPropertyTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @AfterTry
    void cleanup() {
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

    // Feature: online-chat-server, Property 1: Registration uniqueness enforcement
    @Property(tries = 100)
    void duplicateEmailOrUsernameShallBeRejected(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password
    ) {
        // Register the first user
        long countBefore = userRepository.count();
        userService.register(email, username, password);
        long countAfterFirst = userRepository.count();
        assertThat(countAfterFirst).isEqualTo(countBefore + 1);

        // Attempt duplicate email with different username
        String otherUsername = username + "x";
        assertThatThrownBy(() -> userService.register(email, otherUsername, password))
                .isInstanceOf(ConflictException.class);
        assertThat(userRepository.count()).isEqualTo(countAfterFirst);

        // Attempt duplicate username with different email
        String otherEmail = "other" + email;
        assertThatThrownBy(() -> userService.register(otherEmail, username, password))
                .isInstanceOf(ConflictException.class);
        assertThat(userRepository.count()).isEqualTo(countAfterFirst);
    }

    // Feature: online-chat-server, Property 2: Password hash round-trip
    @Property(tries = 100)
    void passwordHashRoundTrip(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password
    ) {
        UserDto userDto = userService.register(email, username, password);

        User persisted = userRepository.findById(userDto.id()).orElseThrow();
        String storedHash = persisted.getPasswordHash();

        // Stored hash must be a valid BCrypt hash (starts with $2)
        assertThat(storedHash).startsWith("$2");

        // BCrypt.checkpw with the original plaintext must return true
        assertThat(BCrypt.checkpw(password, storedHash)).isTrue();
    }

    // Feature: online-chat-server, Property 3: Username immutability
    @Property(tries = 100)
    void usernameShallBeImmutable(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password
    ) {
        UserDto userDto = userService.register(email, username, password);

        // Attempt to change username via updateProfile
        String newUsername = username + "changed";
        assertThatThrownBy(() -> userService.updateProfile(userDto.id(), "New Display", newUsername))
                .isInstanceOf(ForbiddenException.class);

        // Username must remain unchanged
        User reloaded = userRepository.findById(userDto.id()).orElseThrow();
        assertThat(reloaded.getUsername()).isEqualTo(username);
    }
}
