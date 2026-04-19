package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug Condition Exploration Tests — confirms each of the 7 bugs exists on unfixed code.
 *
 * <p>These tests are EXPECTED TO FAIL (i.e., the assertions confirm the buggy behavior).
 * Once the bugs are fixed, these same tests should PASS with the corrected assertions.
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11, 1.12
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class BugConditionExplorationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private DirectChatService directChatService;

    @Autowired
    private UserRepository userRepository;

    private User userA;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto userADto = userService.register("bugtest-a-" + suffix + "@test.com", "buga" + suffix, "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
    }

    // ---- Bug 1: Message Edit Fails with "roomId: must not be null" ----

    /**
     * Bug 1: PUT /api/rooms/{roomId}/messages/{id} with body {"content": "test"} returns 400
     * because ChatMessageRequest requires @NotNull roomId.
     *
     * The edit endpoint reuses ChatMessageRequest which has @NotNull UUID roomId.
     * When the frontend sends only {"content": "new text"}, the validation fires on the
     * missing roomId field, returning 400 instead of 200.
     *
     * EXPECTED ON UNFIXED CODE: The edit request returns 400 (bug confirmed).
     * EXPECTED AFTER FIX: The edit request returns 200 (content-only body accepted).
     *
     * Validates: Requirements 1.1, 1.2
     */
    @Test
    @DisplayName("Bug 1: Edit message with content-only body should succeed but returns 400 due to shared DTO")
    void bug1_editMessageWithContentOnlyBody_returns400() throws Exception {
        Room room = roomService.getRoomById(
                roomService.createRoom("bug1-room-" + UUID.randomUUID().toString().substring(0, 8),
                        "test", RoomVisibility.PUBLIC, userA).id());

        // Send a message first (with roomId in body as required by ChatMessageRequest)
        Message message = messageService.sendMessage(room, userA, "Original content", null);

        // Try to edit with content-only body (no roomId) — this is what the frontend sends
        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), message.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Updated content"}
                                """))
                // BUG: Returns 400 because ChatMessageRequest requires roomId
                // AFTER FIX: Should return 200
                .andExpect(status().isOk());
    }

    // ---- Bug 2: LazyInitializationException on WebSocket Message Send ----

    /**
     * Bug 2: ChatMessageHandler.sendMessage() calls roomMemberRepository.findByRoom(room)
     * instead of roomMemberRepository.findByRoomWithUsers(room). The findByRoom method
     * returns RoomMember entities with lazy-loaded user associations. When the handler
     * accesses member.getUser().getId() outside the service transaction, it triggers
     * LazyInitializationException.
     *
     * We verify this by inspecting the ChatMessageHandler source code to confirm it
     * uses findByRoom (the buggy method) instead of findByRoomWithUsers (the fix).
     *
     * EXPECTED ON UNFIXED CODE: Handler uses findByRoom (bug confirmed).
     * EXPECTED AFTER FIX: Handler uses findByRoomWithUsers.
     *
     * Validates: Requirements 1.3, 1.4
     */
    @Test
    @DisplayName("Bug 2: ChatMessageHandler should use findByRoomWithUsers, not findByRoom")
    void bug2_chatMessageHandler_usesFindByRoomInsteadOfFindByRoomWithUsers() throws IOException {
        Path handlerPath = Path.of("src/main/java/com/bovae/yac/ws/ChatMessageHandler.java");
        assertThat(handlerPath).exists();

        String content = Files.readString(handlerPath);

        // Find the sendMessage method
        int methodStart = content.indexOf("public void sendMessage(");
        assertThat(methodStart).as("sendMessage method should exist in ChatMessageHandler").isGreaterThanOrEqualTo(0);

        String methodBody = content.substring(methodStart);

        // BUG: The handler uses findByRoom (without JOIN FETCH) which causes LazyInitializationException
        // AFTER FIX: The handler should use findByRoomWithUsers (with JOIN FETCH)
        assertThat(methodBody)
                .as("ChatMessageHandler.sendMessage should use findByRoomWithUsers to avoid LazyInitializationException")
                .contains("findByRoomWithUsers");
    }

    // ---- Bug 3: Online Status Always Shows Gray (Missing fetchInitialPresence call) ----

    /**
     * Bug 3: subscribeToAllVisibleUsers() in presence.js subscribes to presence topics
     * but does NOT call fetchInitialPresence() for those user IDs. This means
     * server-rendered member list dots never get their initial status fetched.
     *
     * We verify this by inspecting the source code of presence.js — specifically
     * the body of subscribeToAllVisibleUsers function.
     *
     * EXPECTED ON UNFIXED CODE: subscribeToAllVisibleUsers does NOT contain fetchInitialPresence call.
     * EXPECTED AFTER FIX: subscribeToAllVisibleUsers DOES call fetchInitialPresence.
     *
     * Validates: Requirements 1.5, 1.6
     */
    @Test
    @DisplayName("Bug 3: subscribeToAllVisibleUsers() does not call fetchInitialPresence()")
    void bug3_subscribeToAllVisibleUsers_doesNotCallFetchInitialPresence() throws IOException {
        Path presenceJs = Path.of("src/main/resources/static/js/presence.js");
        assertThat(presenceJs).exists();

        String content = Files.readString(presenceJs);

        // Find the subscribeToAllVisibleUsers function body
        int funcStart = content.indexOf("function subscribeToAllVisibleUsers()");
        assertThat(funcStart).as("subscribeToAllVisibleUsers function should exist").isGreaterThanOrEqualTo(0);

        // Find the closing brace of this function by tracking brace depth
        int braceStart = content.indexOf("{", funcStart);
        int depth = 0;
        int funcEnd = -1;
        for (int i = braceStart; i < content.length(); i++) {
            if (content.charAt(i) == '{') {
                depth++;
            } else if (content.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    funcEnd = i + 1;
                    break;
                }
            }
        }
        assertThat(funcEnd).as("Function closing brace should be found").isGreaterThan(funcStart);

        String funcBody = content.substring(funcStart, funcEnd);

        // BUG: The function does NOT call fetchInitialPresence
        // AFTER FIX: The function SHOULD call fetchInitialPresence
        assertThat(funcBody)
                .as("subscribeToAllVisibleUsers should call fetchInitialPresence after subscribing")
                .contains("fetchInitialPresence");
    }

    // ---- Bug 4: Password Reset Doesn't Actually Change the Password ----

    /**
     * Bug 4: PasswordService.resetPassword() obtains the User via resetToken.getUser()
     * which returns a Hibernate lazy proxy. Setting passwordHash on the proxy may not
     * trigger dirty checking properly, so the password is not actually persisted.
     *
     * The fix is to use userRepository.findById() to get a fully-loaded managed entity.
     * We verify the bug condition by checking that the code uses resetToken.getUser()
     * instead of userRepository.findById().
     *
     * Note: This bug is difficult to reproduce in a test context because the test's
     * @Transactional keeps the persistence context open, masking the lazy proxy issue.
     *
     * EXPECTED ON UNFIXED CODE: Code uses resetToken.getUser() (lazy proxy — bug confirmed).
     * EXPECTED AFTER FIX: Code uses userRepository.findById() (fully loaded entity).
     *
     * Validates: Requirements 1.7, 1.8
     */
    @Test
    @DisplayName("Bug 4: resetPassword() should use userRepository.findById(), not resetToken.getUser()")
    void bug4_resetPassword_usesLazyProxyInsteadOfFindById() throws IOException {
        Path passwordServicePath = Path.of("src/main/java/com/bovae/yac/service/PasswordService.java");
        assertThat(passwordServicePath).exists();

        String content = Files.readString(passwordServicePath);

        // Find the resetPassword method
        int methodStart = content.indexOf("public void resetPassword(");
        assertThat(methodStart).as("resetPassword method should exist").isGreaterThanOrEqualTo(0);

        // Find the end of the method (next public method or end of class)
        int methodEnd = content.indexOf("public void changePassword(", methodStart + 1);
        if (methodEnd == -1) {
            methodEnd = content.length();
        }

        String methodBody = content.substring(methodStart, methodEnd);

        // BUG: Uses resetToken.getUser() which returns a lazy proxy
        // AFTER FIX: Should use userRepository.findById(resetToken.getUser().getId())
        assertThat(methodBody)
                .as("resetPassword should use userRepository.findById() to get a fully-loaded User entity")
                .contains("userRepository.findById(");
    }

    // ---- Bug 5: Room Invitation to Non-Friend User Is Invisible ----

    /**
     * Bug 5: populateRoomInvitations() in sidebar.js renders the invitation section
     * with CSS class "collapse" but without "show", making it hidden by default.
     * Users have no visual indicator that invitations exist.
     *
     * We verify this by inspecting the source code of sidebar.js.
     *
     * EXPECTED ON UNFIXED CODE: The collapse div does NOT have "show" class.
     * EXPECTED AFTER FIX: The collapse div has "collapse show" class.
     *
     * Validates: Requirements 1.9, 1.10
     */
    @Test
    @DisplayName("Bug 5: populateRoomInvitations() renders collapse div without 'show' class")
    void bug5_populateRoomInvitations_collapseDivWithoutShow() throws IOException {
        Path sidebarJs = Path.of("src/main/resources/static/js/sidebar.js");
        assertThat(sidebarJs).exists();

        String content = Files.readString(sidebarJs);

        // Find the populateRoomInvitations function
        int funcStart = content.indexOf("function populateRoomInvitations()");
        assertThat(funcStart).as("populateRoomInvitations function should exist").isGreaterThanOrEqualTo(0);

        // Find the collapse div creation
        // The buggy code sets: collapseDiv.className = 'collapse';
        // The fixed code should set: collapseDiv.className = 'collapse show';
        int funcEnd = content.indexOf("\n  function ", funcStart + 1);
        if (funcEnd == -1) {
            funcEnd = content.length();
        }

        String funcBody = content.substring(funcStart, funcEnd);

        // BUG: The className is set to 'collapse' without 'show'
        // AFTER FIX: The className should be 'collapse show'
        assertThat(funcBody)
                .as("Invitation section should have 'collapse show' class to be visible by default")
                .contains("collapse show");
    }

    // ---- Bug 6: Saved Messages Room Shows Internal UUID Name ----

    /**
     * Bug 6: ChatWebController passes the raw Room entity to the template.
     * For Saved Messages rooms, the name is "saved-messages-{UUID}" which is displayed
     * directly in the chat header instead of a friendly "Saved Messages" name.
     *
     * We verify this by checking that the controller does NOT add a displayName
     * model attribute for Saved Messages rooms.
     *
     * EXPECTED ON UNFIXED CODE: The room view shows the raw UUID-based name.
     * EXPECTED AFTER FIX: The room view shows "Saved Messages".
     *
     * Validates: Requirements 1.11
     */
    @Test
    @DisplayName("Bug 6: Saved Messages room view should display friendly name, not raw UUID")
    void bug6_savedMessagesRoom_displaysRawUuidName() throws Exception {
        // Create a Saved Messages room for userA
        RoomDto savedRoom = directChatService.getOrCreateSavedMessages(userA);

        // Verify the room name starts with "saved-messages-"
        Room room = roomService.getRoomById(savedRoom.id());
        assertThat(room.getName()).startsWith("saved-messages-");

        // Access the room view — the response should contain a friendly name
        String responseBody = mockMvc.perform(get("/chat/rooms/{id}", savedRoom.id())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // BUG: The HTML contains the raw "saved-messages-{UUID}" name
        // AFTER FIX: The HTML should contain "Saved Messages" instead
        assertThat(responseBody)
                .as("Room view should not display raw saved-messages-UUID name")
                .doesNotContain(room.getName());
    }

    // ---- Bug 7: Sessions Page Crashes with 500 Error ----

    /**
     * Bug 7: The Thymeleaf template profile/sessions.html uses th:each="session : ${sessions}"
     * where "session" is a reserved word in Thymeleaf's web context. This causes
     * IllegalArgumentException when rendering the page with non-empty sessions.
     *
     * We verify this by inspecting the template source to confirm it uses the
     * reserved variable name "session" as the iteration variable.
     *
     * EXPECTED ON UNFIXED CODE: Template uses "session" as iteration variable (bug confirmed).
     * EXPECTED AFTER FIX: Template uses a non-reserved name like "sess".
     *
     * Validates: Requirements 1.12
     */
    @Test
    @DisplayName("Bug 7: sessions.html should not use 'session' as Thymeleaf iteration variable")
    void bug7_sessionsTemplate_usesReservedVariableName() throws IOException {
        Path sessionsHtml = Path.of("src/main/resources/templates/profile/sessions.html");
        assertThat(sessionsHtml).exists();

        String content = Files.readString(sessionsHtml);

        // BUG: The template uses th:each="session : ${sessions}" where "session" is reserved
        // AFTER FIX: Should use a non-reserved name like th:each="sess : ${sessions}"
        assertThat(content)
                .as("sessions.html should not use 'session' as th:each iteration variable (reserved word)")
                .doesNotContain("th:each=\"session :");
    }
}
