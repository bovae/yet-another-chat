package com.bovae.yac.integration;

import static org.hamcrest.Matchers.empty;
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
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.RoomMemberService;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for RoomApiController: room creation, catalog search, join, leave, delete,
 * and unauthenticated access.
 *
 * Validates Requirements: 1.1, 1.3, 7.2
 * Validates Correctness Properties: CP 12, CP 13, CP 14, CP 15
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class RoomApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private ModerationService moderationService;

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

    private User register(String email, String username) {
        return userRepository
                .findById(userService.register(email, username, "testpass123").id())
                .orElseThrow();
    }

    // ---- Member list ordered Owner → Admin → Member, then username (R3-05) ----

    @Test
    void listMembers_orderedByRoleThenUsername() throws Exception {
        Room room = roomService.getRoomById(roomService
                .createRoom("ordering-room", "desc", RoomVisibility.PUBLIC, userA)
                .id());
        User zoe = register("zoe@test.com", "zoe");
        User carol = register("carol@test.com", "carol");
        roomMemberService.joinPublicRoom(room, userB); // bob → MEMBER
        roomMemberService.joinPublicRoom(room, zoe); // zoe → MEMBER, promoted below
        roomMemberService.joinPublicRoom(room, carol); // carol → MEMBER
        moderationService.grantAdminRole(room, userA, zoe);

        mockMvc.perform(get("/api/rooms/{id}/members", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].username", is("alice"))) // OWNER first
                .andExpect(jsonPath("$[1].username", is("zoe"))) // ADMIN before members despite name
                .andExpect(jsonPath("$[2].username", is("bob"))) // MEMBER, alphabetical
                .andExpect(jsonPath("$[3].username", is("carol")));
    }

    // ---- Room creation with valid CSRF token returns 201 (verifies CSRF fix) ----

    @Test
    void createRoom_withValidCsrfToken_returns201() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "new-room", "description": "A test room", "visibility": "PUBLIC"}
                                """))
                .andExpect(status().isCreated());
    }

    // ---- Catalog search returns only PUBLIC rooms matching search term ----

    @Test
    void searchCatalog_returnsOnlyPublicRoomsMatchingTerm() throws Exception {
        roomService.createRoom("public-alpha", "desc", RoomVisibility.PUBLIC, userA);
        roomService.createRoom("public-beta", "desc", RoomVisibility.PUBLIC, userA);
        roomService.createRoom("private-alpha", "desc", RoomVisibility.PRIVATE, userA);

        mockMvc.perform(get("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("search", "alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name", is("public-alpha")));
    }

    @Test
    void searchCatalog_noMatch_returnsEmptyContent() throws Exception {
        roomService.createRoom("public-room", "desc", RoomVisibility.PUBLIC, userA);

        mockMvc.perform(get("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("search", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
    }

    // ---- Join public room, leave room, delete room by owner ----

    @Test
    void joinPublicRoom_succeeds() throws Exception {
        Room room = roomService.getRoomById(roomService
                .createRoom("join-room", "desc", RoomVisibility.PUBLIC, userA)
                .id());

        mockMvc.perform(post("/api/rooms/{id}/join", room.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void leaveRoom_succeeds() throws Exception {
        Room room = roomService.getRoomById(roomService
                .createRoom("leave-room", "desc", RoomVisibility.PUBLIC, userA)
                .id());
        roomMemberService.joinPublicRoom(room, userB);

        mockMvc.perform(post("/api/rooms/{id}/leave", room.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteRoom_byOwner_succeeds() throws Exception {
        Room room = roomService.getRoomById(roomService
                .createRoom("delete-room", "desc", RoomVisibility.PUBLIC, userA)
                .id());

        mockMvc.perform(delete("/api/rooms/{id}", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify room no longer appears in catalog
        mockMvc.perform(get("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("search", "delete-room"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()));
    }

    // ---- Unauthenticated POST to /api/rooms ----

    @Test
    void createRoom_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "unauth-room", "description": "desc", "visibility": "PUBLIC"}
                                """))
                .andExpect(status().is3xxRedirection());
    }
}
