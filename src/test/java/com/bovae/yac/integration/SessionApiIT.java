package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for SessionApiController: session listing and session termination.
 *
 * Validates Requirements: 7.12
 * Validates Correctness Properties: CP 5
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class SessionApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private UserRepository userRepository;

    private User userA;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("alice@test.com", "alice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
    }

    @AfterEach
    void cleanupSessions() {
        // Sessions are stored in Redis, not covered by @Transactional rollback
        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(userA.getEmail());
        for (String sessionId : sessions.keySet()) {
            sessionRepository.deleteById(sessionId);
        }
    }

    // ---- Session listing ----

    @Test
    void listSessions_withNoSessions_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/sessions")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listSessions_withSessions_returnsAllSessions() throws Exception {
        createSessionForUser(userA.getEmail());
        createSessionForUser(userA.getEmail());

        mockMvc.perform(get("/api/sessions")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].session_id").exists())
                .andExpect(jsonPath("$[0].creation_time").exists())
                .andExpect(jsonPath("$[0].last_accessed_time").exists());
    }

    @Test
    void listSessions_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().is3xxRedirection());
    }

    // ---- Session termination ----

    @Test
    void terminateSession_removesSpecificSession() throws Exception {
        String sessionId = createSessionForUser(userA.getEmail());
        createSessionForUser(userA.getEmail());

        mockMvc.perform(delete("/api/sessions/{id}", sessionId)
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify only one session remains
        mockMvc.perform(get("/api/sessions")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void terminateSession_nonExistentId_returns404() throws Exception {
        // A session id the caller doesn't own (including non-existent) is rejected with 404 (R1-11).
        mockMvc.perform(delete("/api/sessions/{id}", "non-existent-session-id")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void terminateSession_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(delete("/api/sessions/{id}", "some-session-id")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    // ---- Helper ----

    @SuppressWarnings("unchecked")
    private String createSessionForUser(String email) {
        FindByIndexNameSessionRepository<Session> repo =
                (FindByIndexNameSessionRepository<Session>) sessionRepository;
        Session session = repo.createSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                email
        );
        repo.save(session);
        return session.getId();
    }
}
