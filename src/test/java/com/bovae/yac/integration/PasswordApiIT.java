package com.bovae.yac.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PasswordService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for PasswordApiController: password change and password reset flows.
 *
 * Validates Requirements: 7.10
 * Validates Correctness Properties: CP 6, CP 7
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class PasswordApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private UserRepository userRepository;

    private User userA;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("alice@test.com", "alice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
    }

    // ---- Password change flow ----

    @Test
    void changePassword_withCorrectCurrentPassword_succeeds() throws Exception {
        mockMvc.perform(post("/api/password/change")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "testpass123", "new_password": "newpass456"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void changePassword_withIncorrectCurrentPassword_returns403() throws Exception {
        mockMvc.perform(post("/api/password/change")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "wrongpassword", "new_password": "newpass456"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void changePassword_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(post("/api/password/change")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "testpass123", "new_password": "newpass456"}
                                """))
                .andExpect(status().is3xxRedirection());
    }

    // ---- Password reset flow ----

    @Test
    void requestPasswordReset_withExistingEmail_returns200() throws Exception {
        mockMvc.perform(post("/api/password/reset-request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@test.com"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void requestPasswordReset_withNonExistentEmail_returns200() throws Exception {
        // Always returns 200 to prevent email enumeration
        mockMvc.perform(post("/api/password/reset-request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@test.com"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_withValidToken_succeeds() throws Exception {
        String rawToken = passwordService.createResetToken(userA);

        mockMvc.perform(post("/api/password/reset")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "new_password": "resetpass789"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk());
    }

    @Test
    void resetPassword_withInvalidToken_returns404() throws Exception {
        mockMvc.perform(post("/api/password/reset")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "invalid-token-value", "new_password": "resetpass789"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void resetPassword_tokenCanOnlyBeUsedOnce() throws Exception {
        String rawToken = passwordService.createResetToken(userA);

        // First use succeeds
        mockMvc.perform(post("/api/password/reset")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "new_password": "resetpass789"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk());

        // Second use fails (token already used)
        mockMvc.perform(post("/api/password/reset")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "new_password": "anotherpass000"}
                                """.formatted(rawToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void changePassword_afterReset_worksWithNewPassword() throws Exception {
        String rawToken = passwordService.createResetToken(userA);

        // Reset password
        mockMvc.perform(post("/api/password/reset")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "new_password": "resetpass789"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk());

        // Change password using the new password as current
        mockMvc.perform(post("/api/password/change")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "resetpass789", "new_password": "finalpass000"}
                                """))
                .andExpect(status().isOk());
    }
}
