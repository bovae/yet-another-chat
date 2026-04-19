package com.bovae.yac.unit;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.entity.PasswordResetToken;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.PasswordResetTokenRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @InjectMocks
    private PasswordService passwordService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("$2a$10$existingHash")
                .build();
    }

    /**
     * Validates CP 7: createResetToken returns a raw token string and persists
     * a PasswordResetToken with a SHA-256 hashed token (not the raw value).
     */
    @Test
    void createResetToken_returnsRawTokenAndPersistsHashedToken() {
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> {
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

        when(passwordResetTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(resetToken));
        when(userRepository.findById(existingUser.getId()))
                .thenReturn(Optional.of(existingUser));
        when(passwordEncoder.encode(newPassword)).thenReturn(encodedNewPassword);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        passwordService.resetPassword(rawToken, newPassword);

        // Token marked as used
        assertThat(resetToken.isUsed()).isTrue();
        verify(passwordResetTokenRepository).save(resetToken);

        // User password updated
        assertThat(existingUser.getPasswordHash()).isEqualTo(encodedNewPassword);
        verify(userRepository).save(existingUser);
        verify(passwordEncoder).encode(newPassword);
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

        when(passwordResetTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(expiredToken));

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
        when(passwordEncoder.matches(currentPassword, existingUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(newPassword)).thenReturn(encodedNewPassword);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

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
        when(passwordEncoder.matches(wrongPassword, existingUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> passwordService.changePassword(userId, wrongPassword, "newPass"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("incorrect");

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }
}
