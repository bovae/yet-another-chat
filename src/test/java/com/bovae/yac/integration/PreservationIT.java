package com.bovae.yac.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preservation Tests — verifies existing correct behavior BEFORE implementing fixes.
 *
 * <p>These tests should PASS on the current unfixed code, establishing a baseline
 * to ensure no regressions are introduced when the seven bugs are fixed.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class PreservationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto userADto =
                userService.register("preserve-a-" + suffix + "@test.com", "preservea" + suffix, "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto =
                userService.register("preserve-b-" + suffix + "@test.com", "preserveb" + suffix, "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
    }

    // ---- Preservation: WebSocket send with full ChatMessageRequest (roomId + content) ----

    /**
     * REST POST /api/rooms/{roomId}/messages with full ChatMessageRequest (roomId + content)
     * continues to create messages successfully. This validates that the WebSocket send path's
     * requirement for roomId in the payload is preserved.
     *
     * Validates: Requirements 3.1, 3.2
     */
    @Test
    @DisplayName("Preservation: REST POST message with full ChatMessageRequest (roomId + content) succeeds")
    void preservation_restPostMessage_withFullChatMessageRequest_succeeds() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "preserve-msg-" + UUID.randomUUID().toString().substring(0, 8), "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());

        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Hello from preservation test!"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content", is("Hello from preservation test!")))
                .andExpect(jsonPath("$.id", notNullValue()));
    }

    // ---- Preservation: REST POST continues to validate and create messages ----

    /**
     * REST POST /api/rooms/{roomId}/messages validates the request body and creates
     * messages with correct fields including watermark and room association.
     *
     * Validates: Requirements 3.3, 3.4
     */
    @Test
    @DisplayName("Preservation: REST POST message validates and creates with correct fields")
    void preservation_restPostMessage_validatesAndCreatesCorrectly() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "preserve-validate-" + UUID.randomUUID().toString().substring(0, 8),
                "test",
                RoomVisibility.PUBLIC,
                userA);
        Room room = roomService.getRoomById(roomDto.id());

        // Valid message creation
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Validated message"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.room_id", is(room.getId().toString())))
                .andExpect(jsonPath("$.watermark", notNullValue()));

        // Empty content should fail validation
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": ""}
                                """.formatted(room.getId())))
                .andExpect(status().isBadRequest());
    }

    // ---- Preservation: Password change flow persists new hash correctly ----

    /**
     * Password change via POST /api/password/change persists the new hash correctly.
     * After changing, the new password should be encoded in the database.
     *
     * Validates: Requirements 3.7, 3.8
     */
    @Test
    @DisplayName("Preservation: Password change flow persists new hash correctly")
    void preservation_passwordChange_persistsNewHash() throws Exception {
        mockMvc.perform(post("/api/password/change")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "testpass123", "new_password": "newpass456"}
                                """))
                .andExpect(status().isOk());

        // Verify the new password is persisted
        User updatedUser = userRepository.findById(userA.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("newpass456", updatedUser.getPasswordHash()))
                .isTrue();
        assertThat(passwordEncoder.matches("testpass123", updatedUser.getPasswordHash()))
                .isFalse();
    }

    // ---- Preservation: Non-DIRECT rooms display their actual stored name ----

    /**
     * Non-DIRECT rooms (PUBLIC, PRIVATE) display their actual stored name in the
     * chat room template. This ensures the room name display logic is preserved
     * for regular rooms when the Saved Messages fix is applied.
     *
     * Validates: Requirements 3.11
     */
    @Test
    @DisplayName("Preservation: Non-DIRECT rooms display their actual stored name in templates")
    void preservation_nonDirectRooms_displayActualStoredName() throws Exception {
        String roomName = "preserve-display-" + UUID.randomUUID().toString().substring(0, 8);
        RoomDto roomDto = roomService.createRoom(roomName, "A test room", RoomVisibility.PUBLIC, userA);

        String responseBody = mockMvc.perform(get("/chat/rooms/{id}", roomDto.id())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // The room name should appear in the HTML (header, title, etc.)
        assertThat(responseBody).contains(roomName);
    }

    // ---- Preservation: Other Thymeleaf templates render without errors ----

    /**
     * Profile and rooms catalog templates render without errors.
     * This ensures template rendering is not broken by any fixes.
     *
     * Validates: Requirements 3.12
     */
    @Test
    @DisplayName("Preservation: Profile and rooms catalog templates render without errors")
    void preservation_otherTemplates_renderWithoutErrors() throws Exception {
        // Profile page
        mockMvc.perform(get("/profile").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Profile")));

        // Rooms catalog page
        mockMvc.perform(get("/rooms/catalog").with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Public Room Catalog")));
    }

    // ---- Preservation: Friend-to-friend invitations display correctly ----

    /**
     * When a friend invites another friend to a room, the invitation appears
     * in the pending invitations API response. This ensures the invitation
     * display for friends is preserved when the non-friend visibility fix is applied.
     *
     * Validates: Requirements 3.9, 3.10
     */
    @Test
    @DisplayName("Preservation: Friend-to-friend invitations display correctly via API")
    void preservation_friendToFriendInvitations_displayCorrectly() throws Exception {
        // Make userA and userB friends
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, "Let's be friends");
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        // Create a private room owned by userA
        RoomDto roomDto = roomService.createRoom(
                "preserve-invite-" + UUID.randomUUID().toString().substring(0, 8),
                "private room",
                RoomVisibility.PRIVATE,
                userA);
        Room room = roomService.getRoomById(roomDto.id());

        // userA invites userB (who is a friend)
        RoomInvitation invitation = RoomInvitation.builder()
                .room(room)
                .inviter(userA)
                .invitee(userB)
                .build();
        roomInvitationRepository.save(invitation);

        // userB should see the pending invitation via the API
        mockMvc.perform(get("/api/rooms/invitations/pending")
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].room_name", is(room.getName())))
                .andExpect(jsonPath("$[0].inviter_username", is(userA.getUsername())));
    }
}
