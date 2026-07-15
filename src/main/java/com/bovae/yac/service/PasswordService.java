package com.bovae.yac.service;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.PasswordResetToken;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.PasswordResetTokenRepository;
import com.bovae.yac.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordService {

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final JavaMailSender mailSender;

    @Value("${app.base-url}")
    private final String baseUrl;

    @Value("${app.mail.from}")
    private final String mailFrom;

    /**
     * Handles a forgot-password request out-of-band (R1-10, design D6): if the email maps to a
     * user, a reset token is created and mailed. The caller always responds generically regardless
     * of whether a user existed — the token is never returned or rendered.
     */
    @Transactional
    public void requestReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String rawToken = createResetToken(user);
            sendResetEmail(user, rawToken);
        });
    }

    /**
     * Generates a password reset token for the given user.
     * The raw token is returned to the caller (e.g. for inclusion in a reset link),
     * while only the SHA-256 hash is persisted.
     */
    @Transactional
    public String createResetToken(User user) {
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = hashToken(rawToken);

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .used(false)
                .build();

        passwordResetTokenRepository.save(resetToken);
        LOG.info("Password reset token created for user: id={}", user.getId());
        return rawToken;
    }

    /**
     * Validates a raw reset token and updates the user's password.
     * The raw token is hashed and looked up in the database.
     * The token must not be expired or already used.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = hashToken(rawToken);

        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid password reset token"));

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new ForbiddenException("Password reset token has expired");
        }

        if (resetToken.isUsed()) {
            throw new ForbiddenException("Password reset token has already been used");
        }

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        User user = userRepository.findById(resetToken.getUser().getId()).orElseThrow();
        user.setPasswordHash(encodePassword(newPassword));
        userRepository.save(user);

        // Invalidate existing sessions; remember-me cookies are keyed on the password hash and
        // so are void once it changes (R1-36).
        invalidateSessions(user.getEmail());

        LOG.info("Password reset completed for user: id={}", user.getId());
    }

    /**
     * Changes the password for a logged-in user.
     * The current password must match the stored hash.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: %s".formatted(userId)));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ForbiddenException("Current password is incorrect");
        }

        user.setPasswordHash(encodePassword(newPassword));
        userRepository.save(user);

        invalidateSessions(user.getEmail());

        LOG.info("Password changed for user: id={}", userId);
    }

    private String encodePassword(String rawPassword) {
        return Objects.requireNonNull(passwordEncoder.encode(rawPassword), "PasswordEncoder returned a null hash");
    }

    private void invalidateSessions(String email) {
        sessionRepository.findByPrincipalName(email).keySet().forEach(sessionRepository::deleteById);
    }

    private void sendResetEmail(User user, String rawToken) {
        String resetLink = baseUrl + "/reset-password?token=" + rawToken;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(user.getEmail());
        message.setSubject("Reset your YAC password");
        message.setText("A password reset was requested for your account.\n\n"
                + "Use this link within the next hour to set a new password:\n" + resetLink
                + "\n\nIf you did not request this, you can ignore this email.");
        try {
            mailSender.send(message);
            LOG.info("Password reset email sent to user: id={}", user.getId());
        } catch (MailException ex) {
            // Never fail the request or leak whether the address exists; the user still sees the
            // generic response. A misconfigured mail host is an operator problem, logged here.
            LOG.error("Failed to send password reset email for user id={}", user.getId(), ex);
        }
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
