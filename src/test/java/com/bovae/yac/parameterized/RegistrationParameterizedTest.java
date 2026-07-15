package com.bovae.yac.parameterized;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.UserService;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parameterized boundary tests for user registration.
 *
 * <p>Validates:
 * <ul>
 *   <li>CP 1 — Registration uniqueness: duplicate email or username is rejected with ConflictException</li>
 *   <li>CP 2 — Password hash round-trip: BCrypt hash of any password verifies correctly</li>
 * </ul>
 *
 * <p>Requirements: 6.4, 6.5
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class RegistrationParameterizedTest {

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    // ---- CP 1: Registration uniqueness with varied email/username combinations ----
    /**
     * Verifies that registering with unique email/username combinations succeeds,
     * including boundary-length values for username (1 char, 50 chars) and email.
     *
     * <p>Validates: CP 1 — each combination is unique and accepted.
     */
    @ParameterizedTest(name = "[{index}] email={0}, username={1}")
    @MethodSource("uniqueRegistrationCombinations")
    void register_uniqueCombinations_succeeds(String email, String username) {
        UserDto userDto = userService.register(email, username, "password123");
        User user = userRepository.findById(userDto.id()).orElseThrow();

        assertNotNull(user.getId());
        assertEquals(email, user.getEmail());
        assertEquals(username, user.getUsername());
    }

    static Stream<Arguments> uniqueRegistrationCombinations() {
        return Stream.of(
                Arguments.of("a@example.com", "a"), // min-length username (1 char)
                Arguments.of("boundary@example.com", "a".repeat(50)), // max-length username (50 chars)
                Arguments.of("user+tag@example.com", "user_with_tag"), // email with plus addressing
                Arguments.of("UPPER@CASE.COM", "mixedCase"), // uppercase email
                Arguments.of("dots.in.local@sub.domain.com", "dotuser"), // dots in email local part
                Arguments.of("numeric123@test.io", "user42") // numeric characters
                );
    }

    /**
     * Verifies that registering with a duplicate email throws ConflictException,
     * regardless of the username used.
     *
     * <p>Validates: CP 1 — email uniqueness enforced.
     */
    @ParameterizedTest(name = "[{index}] duplicateEmail={0}")
    @MethodSource("duplicateEmailCombinations")
    void register_duplicateEmail_throwsConflict(String email, String firstUsername, String secondUsername) {
        userService.register(email, firstUsername, "password123");

        ConflictException ex =
                assertThrows(ConflictException.class, () -> userService.register(email, secondUsername, "password456"));
        assertTrue(ex.getMessage().contains("Email"));
    }

    static Stream<Arguments> duplicateEmailCombinations() {
        return Stream.of(
                Arguments.of("dup@example.com", "first1", "second1"),
                Arguments.of("short@x.co", "first2", "second2"),
                Arguments.of("long.email.address@very-long-domain.example.com", "first3", "second3"),
                Arguments.of("special+chars@test.org", "first4", "second4"),
                Arguments.of("CASE@test.com", "first5", "second5"));
    }

    /**
     * Verifies that registering with a duplicate username throws ConflictException,
     * regardless of the email used.
     *
     * <p>Validates: CP 1 — username uniqueness enforced.
     */
    @ParameterizedTest(name = "[{index}] duplicateUsername={0}")
    @MethodSource("duplicateUsernameCombinations")
    void register_duplicateUsername_throwsConflict(String username, String firstEmail, String secondEmail) {
        userService.register(firstEmail, username, "password123");

        ConflictException ex =
                assertThrows(ConflictException.class, () -> userService.register(secondEmail, username, "password456"));
        assertTrue(ex.getMessage().contains("Username"));
    }

    static Stream<Arguments> duplicateUsernameCombinations() {
        return Stream.of(
                Arguments.of("dupuser", "first1@test.com", "second1@test.com"),
                Arguments.of("x", "first2@test.com", "second2@test.com"), // min-length username
                Arguments.of("a".repeat(50), "first3@test.com", "second3@test.com"), // max-length username
                Arguments.of("user_underscore", "first4@test.com", "second4@test.com"),
                Arguments.of("user123", "first5@test.com", "second5@test.com"));
    }

    // ---- CP 2: Password hash round-trip with varied character sets ----

    /**
     * Verifies that the password hash round-trip works correctly for passwords
     * containing ASCII, Unicode, special characters, and boundary lengths.
     *
     * <p>Validates: CP 2 — BCrypt hash of any password verifies correctly via PasswordEncoder.matches().
     */
    @ParameterizedTest(name = "[{index}] password={0}")
    @MethodSource("passwordVariations")
    void register_passwordHashRoundTrip_verifies(String password, String description) {
        UserDto userDto = userService.register(
                description.replaceAll("\\s+", "") + "@test.com", description.replaceAll("\\s+", ""), password);
        User user = userRepository.findById(userDto.id()).orElseThrow();

        assertNotNull(user.getPasswordHash());
        assertTrue(
                passwordEncoder.matches(password, user.getPasswordHash()),
                "Password hash should verify for: " + description);
    }

    static Stream<Arguments> passwordVariations() {
        return Stream.of(
                Arguments.of("simpleASCII123", "ascii"),
                Arguments.of("p@$$w0rd!#%^&*()", "specialchars"),
                Arguments.of("пароль密码パスワード", "unicode"),
                Arguments.of("a", "singlechar"),
                Arguments.of("a".repeat(72), "maxbcrypt72"), // BCrypt processes up to 72 bytes
                Arguments.of("emoji🔑🛡️🔒pass", "emojipwd"),
                Arguments.of("   spaces   around   ", "spacepwd"));
    }
}
