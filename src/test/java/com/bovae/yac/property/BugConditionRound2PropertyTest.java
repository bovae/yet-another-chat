package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.PasswordService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug condition exploration property tests for Round 2 bugs.
 *
 * <p>These tests encode the EXPECTED (correct) behavior. On UNFIXED code they are
 * EXPECTED TO FAIL — failure confirms the bugs exist. After fixes are applied,
 * these same tests should PASS.
 *
 * <p>Validates: Requirements 1.1, 1.6, 1.7, 1.10, 1.13, 1.14
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
public class BugConditionRound2PropertyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;
    private User userA;
    private User userB;

    @BeforeTry
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto dtoA = userService.register(
                "round2a-" + suffix + "@test.com", "round2a" + suffix, "password123");
        userA = userRepository.findById(dtoA.id()).orElseThrow();

        UserDto dtoB = userService.register(
                "round2b-" + suffix + "@test.com", "round2b" + suffix, "password123");
        userB = userRepository.findById(dtoB.id()).orElseThrow();
    }

    @AfterTry
    void cleanup() {
        roomInvitationRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Test 1a (Bug 1): PUT /api/rooms/{roomId}/messages/{id} returns 200
    // with populated senderUsername field.
    // Will FAIL with 500 LazyInitializationException on unfixed code because
    // MessageService.editMessage() uses findById (no JOIN FETCH on sender).
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.1
     *
     * For any PUT request to edit a message, the response SHALL return HTTP 200
     * with a JSON body containing a non-null sender_username field.
     *
     * Bug Condition: MessageApiController.toResponse() accesses message.getSender().getUsername()
     * on a lazy proxy after the Hibernate session is closed → LazyInitializationException → 500.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (500 instead of 200).
     */
    @Property(tries = 3)
    void editMessageReturnsValidResponseWithSenderUsername(
            @ForAll("contentStrings") String newContent
    ) throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "bug1-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());

        Message message = messageService.sendMessage(room, userA, "Original content", null);

        String requestBody = """
                {"content": "%s"}
                """.formatted(escapeJson(newContent));

        MvcResult result = mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}",
                        room.getId(), message.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).isNotEmpty();

        String senderUsername = com.jayway.jsonpath.JsonPath.read(body, "$.sender_username");
        assertThat(senderUsername)
                .as("Response must contain populated sender_username")
                .isNotNull()
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 1b (Bug 6): POST /api/password/change with camelCase keys
    // { "currentPassword": "old", "newPassword": "new" } — assert 400.
    // Confirms bug: frontend sends camelCase but Jackson SNAKE_CASE expects
    // snake_case, so fields are null → @NotBlank validation fails.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.6
     *
     * When the frontend sends camelCase keys (currentPassword, newPassword) to
     * POST /api/password/change, Jackson's global SNAKE_CASE strategy cannot map
     * them to the DTO fields. The @NotBlank fields remain null → 400 validation error.
     *
     * This test confirms the bug exists by asserting the request returns 400.
     *
     * EXPECTED OUTCOME on unfixed code: PASSES (400 confirms the bug).
     * EXPECTED OUTCOME after fix: The frontend will send snake_case keys, so this
     * camelCase test should still return 400 (the fix is on the frontend side).
     */
    @Property(tries = 1)
    void passwordChangeWithCamelCaseKeysReturns400() throws Exception {
        mockMvc.perform(post("/api/password/change")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "password123", "newPassword": "newpass456"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // Test 1c (Bug 7): PUT /api/users/me with { "displayName": "New" }
    // camelCase key — assert field is NOT mapped (confirms bug).
    // Jackson SNAKE_CASE expects "display_name" but receives "displayName".
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.7
     *
     * When the frontend sends { "displayName": "New Name" } to PUT /api/users/me,
     * Jackson's global SNAKE_CASE strategy expects "display_name". The camelCase key
     * is not recognized, so the displayName field remains null and the update is a no-op.
     *
     * This test confirms the bug by sending camelCase and verifying the display name
     * does NOT change.
     *
     * EXPECTED OUTCOME on unfixed code: PASSES (display name unchanged confirms bug).
     * EXPECTED OUTCOME after fix: Frontend sends snake_case, so this camelCase test
     * should still show the field is not mapped.
     */
    @Property(tries = 1)
    void displayNameUpdateWithCamelCaseKeyDoesNotPersist() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Updated Display Name"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // The response should show the display_name field unchanged because
        // Jackson could not map "displayName" (camelCase) with SNAKE_CASE strategy
        String returnedDisplayName = com.jayway.jsonpath.JsonPath.read(body, "$.display_name");
        assertThat(returnedDisplayName)
                .as("displayName should NOT be updated when sent as camelCase key (bug condition)")
                .isNotEqualTo("Updated Display Name");
    }
    // -----------------------------------------------------------------------
    // Test 1d (Bug 10): Non-member GET /chat/rooms/{privateRoomId} — assert
    // access is denied. Will FAIL on unfixed code because ChatWebController
    // renders the page for non-members of non-PUBLIC rooms.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.10
     *
     * When a non-member user navigates to a non-PUBLIC room, the system SHALL deny
     * access (403 or redirect). Currently, ChatWebController.roomView() renders the
     * page with full message history regardless of membership.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (200 instead of 403 — bug confirmed).
     */
    @Property(tries = 1)
    void nonMemberAccessToPrivateRoomIsDenied() throws Exception {
        // userA creates a PRIVATE room
        RoomDto roomDto = roomService.createRoom(
                "private-room-" + UUID.randomUUID().toString().substring(0, 8),
                "secret room", RoomVisibility.PRIVATE, userA);

        // userB (non-member) tries to access the room
        mockMvc.perform(get("/chat/rooms/{id}", roomDto.id())
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Test 1e (Bug 13): Fetch pending invitations for user with lazy
    // room/inviter — assert non-empty response with room name.
    // Will FAIL with LazyInitializationException because findByInvitee()
    // returns lazy proxies and the controller accesses them outside a session.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.13
     *
     * When a user has pending room invitations, GET /api/rooms/invitations/pending
     * SHALL return a non-empty list with populated room_name and inviter_username.
     *
     * Bug Condition: RoomInvitationRepository.findByInvitee() returns entities with
     * lazy-loaded room and inviter. The controller accesses inv.getRoom().getName()
     * and inv.getInviter().getUsername() outside a transaction → LazyInitializationException.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (500 due to LazyInitializationException).
     */
    @Property(tries = 1)
    void pendingInvitationsReturnCompleteDataWithRoomName() throws Exception {
        // userA creates a PRIVATE room and invites userB
        RoomDto roomDto = roomService.createRoom(
                "invite-room-" + UUID.randomUUID().toString().substring(0, 8),
                "invite test", RoomVisibility.PRIVATE, userA);
        Room room = roomService.getRoomById(roomDto.id());

        RoomInvitation invitation = RoomInvitation.builder()
                .room(room)
                .inviter(userA)
                .invitee(userB)
                .build();
        roomInvitationRepository.save(invitation);

        // userB fetches pending invitations
        MvcResult result = mockMvc.perform(get("/api/rooms/invitations/pending")
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).isNotEmpty();

        // Parse the response — should be a non-empty array
        net.minidev.json.JSONArray invitations = com.jayway.jsonpath.JsonPath.read(body, "$");
        assertThat(invitations)
                .as("Pending invitations should not be empty")
                .isNotEmpty();

        // First invitation should have room_name populated
        String roomName = com.jayway.jsonpath.JsonPath.read(body, "$[0].room_name");
        assertThat(roomName)
                .as("Invitation must contain populated room_name")
                .isNotNull()
                .isNotEmpty();

        // First invitation should have inviter_username populated
        String inviterUsername = com.jayway.jsonpath.JsonPath.read(body, "$[0].inviter_username");
        assertThat(inviterUsername)
                .as("Invitation must contain populated inviter_username")
                .isNotNull()
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // Test 1f (Bug 14): POST /api/password/reset with camelCase
    // { "token": "valid", "newPassword": "pass" } — assert 400.
    // Confirms bug: Jackson SNAKE_CASE expects "new_password" but receives
    // "newPassword", so the field is null → @NotBlank validation fails.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.14
     *
     * When the frontend sends { "token": "...", "newPassword": "..." } to
     * POST /api/password/reset, Jackson's global SNAKE_CASE strategy cannot map
     * "newPassword" to the DTO field. The @NotBlank newPassword field remains null
     * → 400 validation error.
     *
     * This test confirms the bug exists by asserting the request returns 400.
     * Note: "token" maps correctly (single word, no case difference).
     * Note: The endpoint requires authentication in the current security config.
     *
     * EXPECTED OUTCOME on unfixed code: PASSES (400 confirms the bug).
     */
    @Property(tries = 1)
    void passwordResetWithCamelCaseNewPasswordReturns400() throws Exception {
        // Create a real reset token for userA
        String rawToken = passwordService.createResetToken(userA);

        mockMvc.perform(post("/api/password/reset")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "newPassword": "newpassword123"}
                                """.formatted(rawToken)))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // Arbitrary providers
    // -----------------------------------------------------------------------

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<String> contentStrings() {
        return net.jqwik.api.Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(100);
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
