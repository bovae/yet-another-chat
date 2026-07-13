package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for REST API flows: message CRUD, room CRUD, and friendship lifecycle.
 * Uses MockMvc with authenticated users against a full Spring context with Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class RestApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

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

    // ---- Flow 1: Message CRUD (send, edit, delete, paginated history) ----

    @Test
    void messageCrudFlow() throws Exception {
        Room room = roomService.getRoomById(roomService.createRoom("msg-test-room", "test", RoomVisibility.PUBLIC, userA).id());

        // Send a message
        MvcResult sendResult = mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Hello world!"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content", is("Hello world!")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andReturn();

        String responseBody = sendResult.getResponse().getContentAsString();
        String messageId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.id");

        // Edit the message
        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), messageId)
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Hello world edited!"}
                                """.formatted(room.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", is("Hello world edited!")))
                .andExpect(jsonPath("$.edited", is(true)));

        // Get paginated history
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.messages[0].content", is("Hello world edited!")));

        // Delete the message
        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), messageId)
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify history is empty
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", empty()));
    }

    // ---- Flow 2: Room CRUD (create, search catalog, delete) ----

    @Test
    void roomCrudFlow() throws Exception {
        // Create a room via REST
        mockMvc.perform(post("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "integration-room", "description": "A test room", "visibility": "PUBLIC"}
                                """))
                .andExpect(status().isCreated());

        // Search catalog for the room
        mockMvc.perform(get("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("search", "integration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name", is("integration-room")));

        // Find the room ID from DB
        Room createdRoom = roomRepository.findAll().stream()
                .filter(r -> "integration-room".equals(r.getName()))
                .findFirst()
                .orElseThrow();

        // Delete the room
        mockMvc.perform(delete("/api/rooms/{id}", createdRoom.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify room no longer in catalog
        mockMvc.perform(get("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("search", "integration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
    }

    // ---- Flow 3: Friendship lifecycle (send, accept, list, remove) ----

    @Test
    void friendshipLifecycleFlow() throws Exception {
        // Alice sends friend request to Bob
        mockMvc.perform(post("/api/friends/request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "bob"}
                                """))
                .andExpect(status().isCreated());

        // Get friendship ID from DB
        UUID friendshipId = friendshipRepository.findAll().get(0).getId();

        // Bob accepts the friend request
        mockMvc.perform(post("/api/friends/{id}/accept", friendshipId)
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Alice lists friends — should see Bob
        mockMvc.perform(get("/api/friends")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Alice removes the friendship
        mockMvc.perform(delete("/api/friends/{id}", friendshipId)
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify friend list is empty
        mockMvc.perform(get("/api/friends")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    // ---- Flow 4: Paginated message history with cursor ----

    @Test
    void paginatedHistoryWithCursor() throws Exception {
        Room room = roomService.getRoomById(roomService.createRoom("pagination-room", "test", RoomVisibility.PUBLIC, userA).id());

        // Send 5 messages
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                            .with(user(userA.getEmail()).roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"room_id": "%s", "content": "Message %d"}
                                    """.formatted(room.getId(), i)))
                    .andExpect(status().isCreated());
        }

        // First page opens on the newest 2 messages; older ones exist (R1-01).
        MvcResult page1 = mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.has_more", is(true)))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andReturn();

        Integer before1 = com.jayway.jsonpath.JsonPath.read(
                page1.getResponse().getContentAsString(), "$.next_cursor");

        // Load older via the before cursor (R1-02).
        MvcResult page2 = mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("before", String.valueOf(before1))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.has_more", is(true)))
                .andReturn();

        Integer before2 = com.jayway.jsonpath.JsonPath.read(
                page2.getResponse().getContentAsString(), "$.next_cursor");

        // Oldest page — 1 message remaining, nothing older.
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("before", String.valueOf(before2))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.has_more", is(false)));
    }
}
