package com.bovae.yac.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
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
 * Integration tests for DirectChatApiController: create/get direct chat between
 * friends and list direct chats.
 *
 * Validates Requirements: 7.8
 * Validates Correctness Properties: CP 20
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class DirectChatApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private DirectChatService directChatService;

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

    private void makeFriends(User requester, User recipient) {
        Friendship friendship = friendshipService.sendFriendRequest(requester, recipient, null);
        friendshipService.acceptFriendRequest(friendship.getId(), recipient);
    }

    // ---- Create direct chat between friends ----

    @Test
    void createDirectChat_betweenFriends_returnsOk() throws Exception {
        makeFriends(userA, userB);

        mockMvc.perform(post("/api/direct-chats")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.visibility", is("DIRECT")));
    }

    @Test
    void createDirectChat_idempotent_returnsSameRoom() throws Exception {
        makeFriends(userA, userB);

        // Create the first direct chat via service
        RoomDto existingRoom = directChatService.getOrCreateDirectChat(userA, userB);

        // Request again via API — should return the same room
        mockMvc.perform(post("/api/direct-chats")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(existingRoom.id().toString())));
    }

    // ---- Create direct chat failures ----

    @Test
    void createDirectChat_notFriends_returns403() throws Exception {
        mockMvc.perform(post("/api/direct-chats")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createDirectChat_withSelf_returns200() throws Exception {
        mockMvc.perform(post("/api/direct-chats")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userA.getId())))
                .andExpect(status().isOk());
    }

    // ---- List direct chats ----

    @Test
    void listDirectChats_returnsDirectChatsOnly() throws Exception {
        makeFriends(userA, userB);
        makeFriends(userA, userC);

        directChatService.getOrCreateDirectChat(userA, userB);
        directChatService.getOrCreateDirectChat(userA, userC);

        mockMvc.perform(get("/api/direct-chats").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void listDirectChats_noChats_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/direct-chats").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listDirectChats_onlyShowsChatsForCurrentUser() throws Exception {
        makeFriends(userA, userB);
        makeFriends(userB, userC);

        directChatService.getOrCreateDirectChat(userA, userB);
        directChatService.getOrCreateDirectChat(userB, userC);

        // userA should only see the chat with userB
        mockMvc.perform(get("/api/direct-chats").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // userB should see both chats
        mockMvc.perform(get("/api/direct-chats").with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
