package com.bovae.yac.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.UserBanService;
import com.bovae.yac.service.UserService;
import java.util.UUID;
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
 * Integration tests for UserBanApiController: ban user, unban user,
 * and verify friendship termination side effect on ban.
 *
 * Validates Requirements: 7.5
 * Validates Correctness Properties: CP 11
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class UserBanApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserBanService userBanService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserRepository userRepository;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("alice@test.com", "alice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register("bob@test.com", "bob", "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
    }

    // ---- Ban user ----

    @Test
    void banUser_returns201() throws Exception {
        mockMvc.perform(post("/api/user-bans")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isCreated());
    }

    @Test
    void banUser_alreadyBanned_returns409() throws Exception {
        userBanService.banUser(userA, userB);

        mockMvc.perform(post("/api/user-bans")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void banUser_self_returns409() throws Exception {
        mockMvc.perform(post("/api/user-bans")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userA.getId())))
                .andExpect(status().isConflict());
    }

    // ---- Unban user ----

    @Test
    void unbanUser_returns204() throws Exception {
        UserBan ban = userBanService.banUser(userA, userB);

        mockMvc.perform(delete("/api/user-bans/{id}", ban.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void unbanUser_nonExistentBan_returns404() throws Exception {
        mockMvc.perform(delete("/api/user-bans/{id}", UUID.randomUUID())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---- Friendship termination side effect on ban ----

    @Test
    void banUser_terminatesExistingFriendship() throws Exception {
        // Establish friendship between A and B
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        // Verify friendship exists before ban
        mockMvc.perform(get("/api/friends").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Ban user B
        mockMvc.perform(post("/api/user-bans")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isCreated());

        // Verify friendship is terminated after ban
        mockMvc.perform(get("/api/friends").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void banUser_withNoFriendship_stillSucceeds() throws Exception {
        // Ban without any prior friendship
        mockMvc.perform(post("/api/user-bans")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isCreated());
    }
}
