package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.PasswordResetTokenRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.AuthService;
import com.bovae.yac.service.PasswordService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for PasswordService — password change and reset token lifecycle.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class PasswordPropertyTest {

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterTry
    void cleanup() {
        passwordResetTokenRepository.deleteAll();
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
    Arbitrary<String> newPasswords() {
        return Arbitraries.strings()
                .ascii()
                .ofMinLength(8)
                .ofMaxLength(30)
                .map(p -> "new" + p);
    }

    @Provide
    Arbitrary<String> wrongPasswords() {
        return Arbitraries.strings()
                .ascii()
                .ofMinLength(8)
                .ofMaxLength(30)
                .map(p -> p + "WRONG");
    }

    // Feature: online-chat-server, Property 6: Password change authorization
    /**
     * Validates: Requirements 3.3, 3.4
     *
     * For any logged-in User, a password change request SHALL succeed if and only if
     * the provided current password matches the stored hash. On success, the new password
     * SHALL authenticate; on failure, the old password SHALL remain valid.
     */
    @Property(tries = 20)
    void passwordChangeAuthorization(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("newPasswords") String newPassword,
            @ForAll("wrongPasswords") String wrongCurrentPassword
    ) {
        // Register user
        UserDto userDto = userService.register(email, username, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();

        // Attempt change with correct current password → should succeed
        passwordService.changePassword(user.getId(), password, newPassword);

        // New password SHALL authenticate via AuthService
        UserDetails afterChange = authService.loadUserByUsername(email);
        assertThat(passwordEncoder.matches(newPassword, afterChange.getPassword()))
                .as("New password should authenticate after successful change")
                .isTrue();
        assertThat(passwordEncoder.matches(password, afterChange.getPassword()))
                .as("Old password should no longer authenticate after change")
                .isFalse();

        // Attempt change with wrong current password → should throw ForbiddenException
        if (!wrongCurrentPassword.equals(newPassword)) {
            assertThatThrownBy(() ->
                    passwordService.changePassword(user.getId(), wrongCurrentPassword, "anotherPass123"))
                    .isInstanceOf(ForbiddenException.class);

            // After failed attempt, the current (new) password SHALL remain valid
            UserDetails afterFailedChange = authService.loadUserByUsername(email);
            assertThat(passwordEncoder.matches(newPassword, afterFailedChange.getPassword()))
                    .as("Password should remain unchanged after failed change attempt")
                    .isTrue();
        }
    }

    // Feature: online-chat-server, Property 7: Password reset token lifecycle
    /**
     * Validates: Requirements 3.1, 3.2
     *
     * For any registered User, requesting a password reset SHALL create a token.
     * Using that token with a new password SHALL update the hash (verifiable via login)
     * and invalidate the token so it cannot be reused.
     */
    @Property(tries = 20)
    void passwordResetTokenLifecycle(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("newPasswords") String newPassword
    ) {
        // Register user
        UserDto userDto = userService.register(email, username, password);
        User user = userRepository.findById(userDto.id()).orElseThrow();

        // Request password reset → should return a raw token
        String rawToken = passwordService.createResetToken(user);
        assertThat(rawToken).isNotNull().isNotBlank();

        // Use the token to reset password
        passwordService.resetPassword(rawToken, newPassword);

        // New password SHALL authenticate via AuthService.loadUserByUsername
        UserDetails afterReset = authService.loadUserByUsername(email);
        assertThat(passwordEncoder.matches(newPassword, afterReset.getPassword()))
                .as("New password should authenticate after reset")
                .isTrue();
        assertThat(passwordEncoder.matches(password, afterReset.getPassword()))
                .as("Old password should no longer authenticate after reset")
                .isFalse();

        // Reusing the same token SHALL throw ForbiddenException (token invalidated)
        assertThatThrownBy(() -> passwordService.resetPassword(rawToken, "yetAnotherPass1"))
                .isInstanceOf(ForbiddenException.class);

        // Password should still be the one set during the first reset
        UserDetails afterReuse = authService.loadUserByUsername(email);
        assertThat(passwordEncoder.matches(newPassword, afterReuse.getPassword()))
                .as("Password should remain unchanged after token reuse attempt")
                .isTrue();
    }
}
