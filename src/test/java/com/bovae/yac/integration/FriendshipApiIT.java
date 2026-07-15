package com.bovae.yac.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
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
 * Integration tests for FriendshipApiController: send friend request, accept,
 * decline, list friends, and remove friendship flows.
 *
 * Validates Requirements: 7.4
 * Validates Correctness Properties: CP 10
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class FriendshipApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserRepository userRepository;

    private User userA;
    private User userB;
    private User userC;

    @BeforeEach
    void setUp() {
        userA = IntegrationTestSupport.registerUser(userService, userRepository, "alice@test.com", "alice");
        userB = IntegrationTestSupport.registerUser(userService, userRepository, "bob@test.com", "bob");
        userC = IntegrationTestSupport.registerUser(userService, userRepository, "carol@test.com", "carol");
    }

    // ---- Send friend request ----

    @Test
    void sendFriendRequest_returns201() throws Exception {
        mockMvc.perform(post("/api/friends/request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "bob"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void sendFriendRequest_toNonExistentUser_returns404() throws Exception {
        mockMvc.perform(post("/api/friends/request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "nonexistent"}
                                """))
                .andExpect(status().isNotFound());
    }

    // ---- Accept friend request ----

    @Test
    void acceptFriendRequest_returns200() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);

        mockMvc.perform(post("/api/friends/{id}/accept", friendship.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void acceptFriendRequest_byNonRecipient_returns403() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);

        mockMvc.perform(post("/api/friends/{id}/accept", friendship.getId())
                        .with(user(userC.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Decline friend request ----

    @Test
    void declineFriendRequest_returns200() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);

        mockMvc.perform(post("/api/friends/{id}/decline", friendship.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void declineFriendRequest_byNonRecipient_returns403() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);

        mockMvc.perform(post("/api/friends/{id}/decline", friendship.getId())
                        .with(user(userC.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- List friends ----

    @Test
    void listFriends_returnsAcceptedFriendships() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        mockMvc.perform(get("/api/friends").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("ACCEPTED")));
    }

    @Test
    void listFriends_excludesPendingRequests() throws Exception {
        friendshipService.sendFriendRequest(userA, userB, null);

        mockMvc.perform(get("/api/friends").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listFriends_noFriends_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/friends").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- Remove friend ----

    @Test
    void removeFriend_returns204() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        mockMvc.perform(delete("/api/friends/{id}", friendship.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify friend list is now empty
        mockMvc.perform(get("/api/friends").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void removeFriend_byNonParticipant_returns403() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        mockMvc.perform(delete("/api/friends/{id}", friendship.getId())
                        .with(user(userC.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Full lifecycle: send → accept → list → remove ----

    @Test
    void fullLifecycle_sendAcceptListRemove() throws Exception {
        // Send friend request
        mockMvc.perform(post("/api/friends/request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "bob"}
                                """))
                .andExpect(status().isCreated());

        // Pending request should not appear in friend list
        mockMvc.perform(get("/api/friends").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Accept via service (need the friendship ID)
        Friendship pending = friendshipService.sendFriendRequest(userB, userC, null);
        friendshipService.acceptFriendRequest(pending.getId(), userC);

        // Verify userB now sees userC in friend list
        mockMvc.perform(get("/api/friends").with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("ACCEPTED")));

        // Remove the friendship
        mockMvc.perform(delete("/api/friends/{id}", pending.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify friend list is empty again
        mockMvc.perform(get("/api/friends").with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
