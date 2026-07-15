package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.PasswordResetToken;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.PasswordResetTokenRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PasswordService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link PasswordService}.
 *
 * <p>Validates Correctness Properties: CP 6, CP 7.
 * <p>Requirements: 2.9, 2.10, 2.11, 2.12, 2.13.
 */
@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private PasswordService passwordService;

    private static final String BASE_URL = "https://yac.example";
    private static final String MAIL_FROM = "no-reply@yac.example";

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("$2a$10$existingHash")
                .build();

        // @Value-injected fields are not populated by @InjectMocks; set them so the mail
        // assertions have concrete, non-null values to compare against.
        ReflectionTestUtils.setField(passwordService, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(passwordService, "mailFrom", MAIL_FROM);
    }

    /**
     * Validates CP 7: createResetToken returns a raw token string and persists
     * a PasswordResetToken with a SHA-256 hashed token (not the raw value).
     */
    @Test
    void createResetToken_returnsRawTokenAndPersistsHashedToken() {
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            token.setId(UUID.randomUUID());
            return token;
        });

        String rawToken = passwordService.createResetToken(existingUser);

        assertThat(rawToken).isNotNull().isNotBlank();

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());

        PasswordResetToken persisted = captor.getValue();
        assertThat(persisted.getUser()).isEqualTo(existingUser);
        assertThat(persisted.getTokenHash()).isNotNull().isNotBlank();
        // The persisted hash must differ from the raw token (SHA-256 hashing applied)
        assertThat(persisted.getTokenHash()).isNotEqualTo(rawToken);
        assertThat(persisted.isUsed()).isFalse();
        assertThat(persisted.getExpiresAt()).isAfter(Instant.now());
    }

    /**
     * Validates CP 7: resetPassword with a valid, non-expired, unused token
     * updates the user's password hash and marks the token as used.
     */
    @Test
    void resetPassword_withValidToken_updatesPasswordAndMarksTokenUsed() {
        String rawToken = "some-raw-token";
        String newPassword = "newSecurePass123";
        String encodedNewPassword = "$2a$10$newEncodedHash";

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .id(UUID.randomUUID())
                .user(existingUser)
                .tokenHash("placeholder")
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .used(false)
                .build();

        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(resetToken));
        when(userRepository.findById(existingUser.getId())).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.encode(newPassword)).thenReturn(encodedNewPassword);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Session activeSession = mock(Session.class);
        doReturn(Map.of("session-1", activeSession))
                .when(sessionRepository)
                .findByPrincipalName(existingUser.getEmail());

        passwordService.resetPassword(rawToken, newPassword);

        // Token marked as used
        assertThat(resetToken.isUsed()).isTrue();
        verify(passwordResetTokenRepository).save(resetToken);

        // User password updated
        assertThat(existingUser.getPasswordHash()).isEqualTo(encodedNewPassword);
        verify(userRepository).save(existingUser);
        verify(passwordEncoder).encode(newPassword);

        // Existing sessions invalidated so old remember-me cookies are void (R1-36)
        verify(sessionRepository).deleteById("session-1");
    }

    /**
     * Validates CP 7: resetPassword with an expired token throws ForbiddenException.
     */
    @Test
    void resetPassword_withExpiredToken_throwsForbiddenException() {
        String rawToken = "expired-token";

        PasswordResetToken expiredToken = PasswordResetToken.builder()
                .id(UUID.randomUUID())
                .user(existingUser)
                .tokenHash("placeholder")
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .used(false)
                .build();

        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> passwordService.resetPassword(rawToken, "newPass"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("expired");

        verify(userRepository, never()).save(any());
    }

    /**
     * Validates CP 6: changePassword with the correct current password
     * updates the user's password hash to the newly encoded value.
     */
    @Test
    void changePassword_withCorrectCurrentPassword_updatesHash() {
        UUID userId = existingUser.getId();
        String currentPassword = "currentPass";
        String newPassword = "newSecurePass";
        String encodedNewPassword = "$2a$10$brandNewHash";

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches(currentPassword, existingUser.getPasswordHash()))
                .thenReturn(true);
        when(passwordEncoder.encode(newPassword)).thenReturn(encodedNewPassword);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doReturn(Map.of()).when(sessionRepository).findByPrincipalName(existingUser.getEmail());

        passwordService.changePassword(userId, currentPassword, newPassword);

        assertThat(existingUser.getPasswordHash()).isEqualTo(encodedNewPassword);
        verify(userRepository).save(existingUser);
        verify(passwordEncoder).encode(newPassword);
    }

    /**
     * Validates CP 6: changePassword with an incorrect current password
     * throws ForbiddenException and does not update the hash.
     */
    @Test
    void changePassword_withIncorrectCurrentPassword_throwsForbiddenException() {
        UUID userId = existingUser.getId();
        String wrongPassword = "wrongPass";

        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches(wrongPassword, existingUser.getPasswordHash()))
                .thenReturn(false);

        assertThatThrownBy(() -> passwordService.changePassword(userId, wrongPassword, "newPass"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("incorrect");

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    /**
     * Validates CP 6: changePassword for a non-existent user throws ResourceNotFoundException.
     */
    @Test
    void changePassword_whenUserNotFound_throwsResourceNotFoundException() {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordService.changePassword(missingId, "current", "new"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");

        verify(userRepository, never()).save(any());
    }

    // --- requestReset (out-of-band forgot-password) ---

    /**
     * Validates R1-10 (D6): when the email maps to a user, a reset token is created and mailed.
     */
    @Test
    void requestReset_createsTokenAndSendsEmail_whenUserExists() {
        when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        passwordService.requestReset(existingUser.getEmail());

        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo(MAIL_FROM);
        assertThat(sent.getTo()).containsExactly(existingUser.getEmail());
        assertThat(sent.getSubject()).isEqualTo("Reset your YAC password");
        assertThat(sent.getText())
                .startsWith("A password reset was requested for your account.")
                .contains(BASE_URL + "/reset-password?token=");
    }

    /**
     * Validates R1-10 (D6): a mail-send failure is swallowed so the caller still gets the generic
     * response and the address is never revealed.
     */
    @Test
    void requestReset_swallowsMailException_whenSendFails() {
        when(userRepository.findByEmail(existingUser.getEmail())).thenReturn(Optional.of(existingUser));
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new MailSendException("mail host down", null, Map.of()))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        // Must complete normally despite the mail failure.
        passwordService.requestReset(existingUser.getEmail());

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    // --- hashToken defensive guard (SHA-256 unavailable) ---

    /**
     * The private token hashing wraps a missing SHA-256 provider in an IllegalStateException.
     * SHA-256 is always present on a conformant JRE, so the only way to exercise the guard is to
     * force {@link MessageDigest#getInstance} to throw; entering via createResetToken drives it.
     */
    @Test
    void createResetToken_whenSha256Unavailable_throwsIllegalStateException() {
        try (MockedStatic<MessageDigest> messageDigest = mockStatic(MessageDigest.class)) {
            messageDigest
                    .when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("no SHA-256 here"));

            assertThatThrownBy(() -> passwordService.createResetToken(existingUser))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SHA-256 algorithm not available");

            verify(passwordResetTokenRepository, never()).save(any());
        }
    }
}
