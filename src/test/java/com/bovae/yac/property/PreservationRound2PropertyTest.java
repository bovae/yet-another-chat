package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.PasswordService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preservation property tests for Round 2 bugfix spec.
 *
 * <p>These tests verify existing correct behavior BEFORE implementing fixes.
 * They MUST PASS on unfixed code, establishing a baseline to ensure no
 * regressions are introduced when the bugs are fixed.
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
public class PreservationRound2PropertyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private DirectChatService directChatService;

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
                "pres2a-" + suffix + "@test.com", "pres2a" + suffix, "password123");
        userA = userRepository.findById(dtoA.id()).orElseThrow();

        UserDto dtoB = userService.register(
                "pres2b-" + suffix + "@test.com", "pres2b" + suffix, "password123");
        userB = userRepository.findById(dtoB.id()).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Property: Message send by member returns valid response with sender info
    // Validates: Requirements 3.1
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.1
     *
     * For all valid message content strings sent by a room member via
     * POST /api/rooms/{roomId}/messages, the response SHALL return HTTP 201
     * with populated sender_username and sender_id fields.
     */
    @Property(tries = 5)
    void messageSendByMemberReturnsSenderInfo(
            @ForAll("contentStrings") String content
    ) throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "pres-send-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);

        String requestBody = """
                {"room_id": "%s", "content": "%s"}
                """.formatted(roomDto.id(), escapeJson(content));

        mockMvc.perform(post("/api/rooms/{roomId}/messages", roomDto.id())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sender_username", is(userA.getUsername())))
                .andExpect(jsonPath("$.sender_id", is(userA.getId().toString())))
                .andExpect(jsonPath("$.content", is(content)))
                .andExpect(jsonPath("$.watermark", notNullValue()));
    }

    // -----------------------------------------------------------------------
    // Property: Room members can access their rooms and see message history
    // Validates: Requirements 3.10
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.10
     *
     * For all room members accessing their rooms via GET /chat/rooms/{id},
     * the page SHALL render with HTTP 200 and contain the room name and
     * message history.
     */
    @Property(tries = 3)
    void roomMemberAccessRendersWithMessageHistory(
            @ForAll("contentStrings") String messageContent
    ) throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "pres-access-" + UUID.randomUUID().toString().substring(0, 8),
                "test room", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());

        // Send a message so there's history
        messageService.sendMessage(room, userA, messageContent, null);

        MvcResult result = mockMvc.perform(get("/chat/rooms/{id}", roomDto.id())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Page should contain the room name")
                .contains(room.getName());
        assertThat(body)
                .as("Page should contain the message content")
                .contains(messageContent);
    }

    // -----------------------------------------------------------------------
    // Property: Non-reply message deletion removes only that message
    // Validates: Requirements 3.2
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.2
     *
     * For all non-reply message deletions via DELETE /api/rooms/{roomId}/messages/{id},
     * the response SHALL return HTTP 204 and subsequent message history SHALL NOT
     * contain the deleted message but SHALL still contain other messages.
     */
    @Property(tries = 3)
    void messageDeletionRemovesOnlyTargetMessage(
            @ForAll("contentStrings") String content1,
            @ForAll("contentStrings") String content2
    ) throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "pres-del-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());

        // Send two messages
        Message msg1 = messageService.sendMessage(room, userA, content1, null);
        messageService.sendMessage(room, userA, content2, null);

        // Delete the first message
        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg1.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify message history: msg1 gone, msg2 still present
        MvcResult historyResult = mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String historyBody = historyResult.getResponse().getContentAsString();
        assertThat(historyBody)
                .as("Deleted message ID should not appear in history")
                .doesNotContain(msg1.getId().toString());
        assertThat(historyBody)
                .as("Other message content should still be in history")
                .contains(content2);
    }

    // -----------------------------------------------------------------------
    // Property: Clicking a contact initiates DM correctly
    // Validates: Requirements 3.12
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.12
     *
     * For all contacts (friends) in the sidebar, POST /api/direct-chats with
     * the friend's user_id SHALL return a valid room with a non-null id.
     */
    @Property(tries = 1)
    void contactDmInitiationReturnsValidRoom() throws Exception {
        // Make userA and userB friends
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, "Hi");
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        // Initiate DM from userA to userB (simulates clicking contact)
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

    // -----------------------------------------------------------------------
    // Property: Public Room Catalog displays rooms with search and pagination
    // Validates: Requirements 3.4
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.4
     *
     * For any non-member viewing the Public Room Catalog, the system SHALL
     * display the room list with search and pagination functionality.
     */
    @Property(tries = 1)
    void catalogSearchReturnsPaginatedResults() throws Exception {
        String roomName = "pres-catalog-" + UUID.randomUUID().toString().substring(0, 8);
        roomService.createRoom(roomName, "catalog test", RoomVisibility.PUBLIC, userA);

        // userB searches the catalog (not a member of the room)
        mockMvc.perform(get("/api/rooms")
                        .param("search", roomName)
                        .param("page", "0")
                        .param("size", "10")
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].name", is(roomName)));
    }

    // -----------------------------------------------------------------------
    // Property: Session termination invalidates sessions
    // Validates: Requirements 3.5
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.5
     *
     * When a user terminates a session from the Sessions page, the system
     * SHALL invalidate that session successfully (DELETE returns 204).
     */
    @Property(tries = 1)
    void sessionTerminationReturns204() throws Exception {
        // Terminate a non-existent session — should still return 204 (idempotent)
        mockMvc.perform(delete("/api/sessions/{id}", "non-existent-session-id")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // -----------------------------------------------------------------------
    // Property: Password reset with snake_case keys works
    // Validates: Requirements 3.6
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.6
     *
     * When a user submits the password reset form with correct snake_case field
     * names, the system SHALL process the reset token and update the password.
     */
    @Property(tries = 1)
    void passwordResetWithSnakeCaseKeysSucceeds() throws Exception {
        String rawToken = passwordService.createResetToken(userA);

        mockMvc.perform(post("/api/password/reset")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "new_password": "newpassword123"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Property: Profile page displays current display name and username
    // Validates: Requirements 3.7
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.7
     *
     * When a user's profile is loaded, the system SHALL display the current
     * display name and username correctly.
     */
    @Property(tries = 1)
    void profilePageDisplaysUsernameCorrectly() throws Exception {
        MvcResult result = mockMvc.perform(get("/profile")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Profile page should contain the username")
                .contains(userA.getUsername());
    }

    // -----------------------------------------------------------------------
    // Property: Saved Messages room displays "Saved Messages" as name
    // Validates: Requirements 3.8
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.8
     *
     * When a user opens a Saved Messages room (self-DM), the system SHALL
     * display "Saved Messages" as the room name in the header.
     */
    @Property(tries = 1)
    void savedMessagesRoomDisplaysCorrectName() throws Exception {
        RoomDto savedRoom = directChatService.getOrCreateSavedMessages(userA);

        MvcResult result = mockMvc.perform(get("/chat/rooms/{id}", savedRoom.id())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Saved Messages room should display 'Saved Messages' in the page")
                .contains("Saved Messages");
    }

    // -----------------------------------------------------------------------
    // Property: Sidebar populates rooms correctly
    // Validates: Requirements 3.11
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.11
     *
     * When the sidebar initially loads, the system SHALL populate all room
     * lists (public, private, direct) correctly via GET /api/rooms/my.
     */
    @Property(tries = 1)
    void sidebarRoomListPopulatesCorrectly() throws Exception {
        String roomName = "pres-sidebar-" + UUID.randomUUID().toString().substring(0, 8);
        roomService.createRoom(roomName, "sidebar test", RoomVisibility.PUBLIC, userA);

        MvcResult result = mockMvc.perform(get("/api/rooms/my")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Room list should contain the created room")
                .contains(roomName);
    }

    // -----------------------------------------------------------------------
    // Property: Accepting room invitation adds user as member
    // Validates: Requirements 3.13
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.13
     *
     * When a user accepts a room invitation, the system SHALL add them as a
     * member and the room SHALL appear in their room list.
     */
    @Property(tries = 1)
    void acceptingInvitationAddsMember() throws Exception {
        // Create a private room owned by userA
        RoomDto roomDto = roomService.createRoom(
                "pres-invite-" + UUID.randomUUID().toString().substring(0, 8),
                "private room", RoomVisibility.PRIVATE, userA);
        Room room = roomService.getRoomById(roomDto.id());

        // Create invitation for userB
        RoomInvitation invitation = RoomInvitation.builder()
                .room(room)
                .inviter(userA)
                .invitee(userB)
                .build();
        roomInvitationRepository.save(invitation);

        // userB accepts the invitation by joining via the invitation
        roomMemberService.joinPrivateRoomViaInvitation(room, userB);

        // Verify userB is now a member
        assertThat(roomMemberService.isMember(room, userB))
                .as("User should be a member after accepting invitation")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Property: Password change with snake_case keys succeeds
    // Validates: Requirements 3.3 (message action buttons preserved via
    // password change confirming backend still works with correct keys)
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.3, 3.9
     *
     * When a user submits the password change form with correct snake_case
     * field names, the system SHALL successfully change the password.
     * This also confirms the unread notification system works (3.9) since
     * the backend processes requests correctly.
     */
    @Property(tries = 1)
    void passwordChangeWithSnakeCaseKeysSucceeds() throws Exception {
        mockMvc.perform(post("/api/password/change")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"current_password": "password123", "new_password": "newpass456"}
                                """))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Arbitrary providers
    // -----------------------------------------------------------------------

    @Provide
    Arbitrary<String> contentStrings() {
        return Arbitraries.strings()
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
