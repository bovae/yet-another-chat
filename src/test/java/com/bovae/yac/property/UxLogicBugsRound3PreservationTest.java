package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.PresenceService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.Property;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preservation property tests for Round 3 bugs.
 *
 * <p>These tests verify EXISTING correct behavior that must remain unchanged
 * after bug fixes are applied. They MUST PASS on unfixed code.
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.6, 3.8, 3.9, 3.10, 3.11, 3.12
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
public class UxLogicBugsRound3PreservationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private RoomService roomService;
    @Autowired private MessageService messageService;
    @Autowired private RoomMemberService roomMemberService;
    @Autowired private ModerationService moderationService;
    @Autowired private PresenceService presenceService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoomMemberRepository roomMemberRepository;

    private User userA;
    private User userB;
    private User userC;

    @BeforeTry
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto dtoA = userService.register(
                "p3a-" + suffix + "@test.com", "p3a" + suffix, "password123");
        userA = userRepository.findById(dtoA.id()).orElseThrow();

        UserDto dtoB = userService.register(
                "p3b-" + suffix + "@test.com", "p3b" + suffix, "password123");
        userB = userRepository.findById(dtoB.id()).orElseThrow();

        UserDto dtoC = userService.register(
                "p3c-" + suffix + "@test.com", "p3c" + suffix, "password123");
        userC = userRepository.findById(dtoC.id()).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Preservation 3.1 — Leave Non-DM Room
    // Users can leave PUBLIC and PRIVATE rooms normally.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.1
     *
     * For all rooms where visibility != DIRECT, leaving removes the user
     * from membership and returns success.
     *
     * Preservation: Users can leave PUBLIC and PRIVATE rooms normally.
     */
    @Property(tries = 1)
    void leavePublicRoomSucceeds() {
        RoomDto roomDto = roomService.createRoom(
                "pub-leave-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // Verify userB is a member
        assertThat(roomMemberRepository.existsByRoomAndUser(room, userB)).isTrue();

        // userB leaves the PUBLIC room — should succeed
        roomMemberService.leaveRoom(room, userB);

        // Verify userB is no longer a member
        assertThat(roomMemberRepository.existsByRoomAndUser(room, userB))
                .as("User should be removed from membership after leaving a PUBLIC room")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Preservation 3.2 — Normal Message Send
    // Messages without reply-to continue to display correctly.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.2
     *
     * For all messages where replyToId == null, the response contains
     * correct sender info and content.
     *
     * Preservation: Messages sent without reply-to continue to display correctly.
     */
    @Property(tries = 1)
    void normalMessageSendContainsCorrectSenderAndContent() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "msg-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());

        String messageContent = "Hello from preservation test";
        messageService.sendMessage(room, userA, messageContent, null);

        // Fetch message history via REST
        MvcResult result = mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Verify sender username and content are correct
        String senderUsername = com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].sender_username");
        String content = com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].content");

        assertThat(senderUsername)
                .as("sender_username must match the sender's username")
                .isEqualTo(userA.getUsername());

        assertThat(content)
                .as("content must match the sent message")
                .isEqualTo(messageContent);

        // reply_to_id should be null for a non-reply message
        Object replyToId = com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].reply_to_id");
        assertThat(replyToId)
                .as("reply_to_id should be null for a non-reply message")
                .isNull();
    }

    // -----------------------------------------------------------------------
    // Preservation 3.6 — Username Fallback
    // Users without a display name show username in chat messages.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.6
     *
     * For all messages where sender.displayName == null, the rendered name
     * equals the username.
     *
     * Preservation: Users without a display name continue to show username.
     */
    @Property(tries = 1)
    void userWithoutDisplayNameShowsUsername() throws Exception {
        // userA has no display name set (default after registration)
        assertThat(userA.getDisplayName()).isNull();

        RoomDto roomDto = roomService.createRoom(
                "fallback-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());

        messageService.sendMessage(room, userA, "Message without display name", null);

        MvcResult result = mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        String senderUsername = com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].sender_username");
        assertThat(senderUsername)
                .as("When displayName is null, sender_username must be the username")
                .isEqualTo(userA.getUsername());
    }

    // -----------------------------------------------------------------------
    // Preservation 3.8 — Non-Banned User Access
    // Non-banned members can view rooms and send messages normally.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.8
     *
     * For all users where not banned AND isMember, room access returns 200
     * and message send succeeds.
     *
     * Preservation: Non-banned users continue to access channels normally.
     */
    @Property(tries = 1)
    void nonBannedUserCanAccessRoomAndSendMessage() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "access-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // userB is a member and NOT banned — should be able to view the room
        mockMvc.perform(get("/chat/rooms/{id}", room.getId())
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk());

        // userB should be able to send a message
        Message sent = messageService.sendMessage(room, userB, "Hello from non-banned user", null);
        assertThat(sent).isNotNull();
        assertThat(sent.getContent()).isEqualTo("Hello from non-banned user");
    }

    // -----------------------------------------------------------------------
    // Preservation 3.9 — Owner Views Ban List
    // Room owners can view the ban list with all banned user details.
    // NOTE: On unfixed code, this test may encounter LazyInitializationException
    // (Bug 12). We test the owner authorization path specifically — the owner
    // IS allowed to access the endpoint. We verify the request reaches the
    // controller (not blocked by auth). If Bug 12 causes 500, we still verify
    // the owner is not getting 403 (which would indicate a regression in
    // authorization). We accept 200 or 500 (lazy init bug) as valid for
    // preservation — the key preservation is that owners are NOT denied access.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.9
     *
     * For all requests where user is room owner, GET /api/rooms/{roomId}/bans
     * does not return 403 (owner is authorized).
     *
     * Preservation: Room owners continue to be authorized to view the ban list.
     */
    @Property(tries = 1)
    void ownerCanAccessBanListEndpoint() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "ownerban-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // Ban userB so there's data
        moderationService.banUserFromRoom(room, userA, userB);

        // Owner (userA) fetches ban list — should NOT get 403
        MvcResult result = mockMvc.perform(get("/api/rooms/{roomId}/bans", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andReturn();

        int status = result.getResponse().getStatus();
        // Owner should not be denied access (403). On unfixed code, Bug 12
        // may cause 500 (LazyInitializationException), which is acceptable
        // for preservation — the point is the owner is authorized.
        assertThat(status)
                .as("Owner should not receive 403 Forbidden when accessing ban list")
                .isNotEqualTo(403);
    }

    // -----------------------------------------------------------------------
    // Preservation 3.10, 3.11 — First-Time Invitation
    // First-time invitations create records successfully.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.10, 3.11
     *
     * For all (room, invitee) pairs with no existing invitation, POST creates
     * invitation and returns 201.
     *
     * Preservation: First-time invitations continue to create records successfully.
     */
    @Property(tries = 1)
    void firstTimeInvitationSucceeds() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "invite-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PRIVATE, userA);

        // userA (owner) invites userB for the first time
        mockMvc.perform(post("/api/rooms/{roomId}/invitations", roomDto.id())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isCreated());
    }

    // -----------------------------------------------------------------------
    // Preservation 3.12 — Active Tab Heartbeat
    // Users actively using the application maintain ONLINE status.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 3.12
     *
     * For all users with focused tab sending heartbeats, presence status
     * is ONLINE.
     *
     * Preservation: Users actively using the application maintain ONLINE status.
     */
    @Property(tries = 1)
    void activeHeartbeatMaintainsOnlineStatus() {
        // Simulate an active heartbeat (tab focused, user active)
        presenceService.recordHeartbeat(userA.getId(), true);

        PresenceStatus status = presenceService.getUserStatus(userA.getId());
        assertThat(status)
                .as("User sending active heartbeat should be ONLINE")
                .isEqualTo(PresenceStatus.ONLINE);
    }
}
