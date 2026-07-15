package com.bovae.yac.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for web controllers: page rendering for authenticated users,
 * unauthenticated redirects, and public path accessibility.
 *
 * Validates Requirements: 7.13, 7.14, 14.12
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class WebControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private com.bovae.yac.repository.UserRepository userRepository;

    private User userA;

    @BeforeEach
    void setUp() {
        com.bovae.yac.model.dto.UserDto userADto = userService.register("alice@test.com", "alice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
    }

    // ---- Authenticated users get 200 with correct templates ----

    @Test
    void loginPage_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/login").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    @Test
    void registerPage_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/register").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"));
    }

    @Test
    void chatPage_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/chat").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("chat/index"));
    }

    @Test
    void roomsCatalog_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/rooms/catalog").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("rooms/catalog"));
    }

    @Test
    void profilePage_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/profile").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("profile/index"));
    }

    // ---- Unauthenticated access to /chat redirects to /login ----

    @Test
    void chatPage_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/chat")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    // ---- Public paths accessible without authentication ----

    @Test
    void loginPage_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("auth/login"));
    }

    @Test
    void registerPage_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/register")).andExpect(status().isOk()).andExpect(view().name("auth/register"));
    }

    @Test
    void healthEndpoint_unauthenticated_returns200() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
