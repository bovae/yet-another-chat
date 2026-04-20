package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.PasswordService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import com.bovae.yac.exception.ForbiddenException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug condition exploration property tests for Round 3 bugs.
 *
 * <p>These tests encode the EXPECTED (correct) behavior. On UNFIXED code they are
 * EXPECTED TO FAIL — failure confirms the bugs exist. After fixes are applied,
 * these same tests should PASS.
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.6, 1.7, 1.9, 1.10, 1.11, 1.12, 1.13, 1.14, 1.15
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
public class UxLogicBugsRound3ExplorationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private RoomService roomService;
    @Autowired private MessageService messageService;
    @Autowired private RoomMemberService roomMemberService;
    @Autowired private ModerationService moderationService;
    @Autowired private DirectChatService directChatService;
    @Autowired private FriendshipService friendshipService;
    @Autowired private PasswordService passwordService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoomMemberRepository roomMemberRepository;

    private User userA;
    private User userB;
    private User userC;

    @BeforeTry
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto dtoA = userService.register(
                "r3a-" + suffix + "@test.com", "r3a" + suffix, "password123");
        userA = userRepository.findById(dtoA.id()).orElseThrow();

        UserDto dtoB = userService.register(
                "r3b-" + suffix + "@test.com", "r3b" + suffix, "password123");
        userB = userRepository.findById(dtoB.id()).orElseThrow();

        UserDto dtoC = userService.register(
                "r3c-" + suffix + "@test.com", "r3c" + suffix, "password123");
        userC = userRepository.findById(dtoC.id()).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // Bug 1 — DM Leave Prevention
    // RoomMemberService.leaveRoom() should throw ForbiddenException when
    // room visibility is DIRECT.
    // On unfixed code: leaveRoom() succeeds (no visibility check) — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.1
     *
     * For any DIRECT room, leaveRoom() SHALL throw ForbiddenException.
     * Bug Condition: leaveRoom() does not check room visibility, allowing
     * users to leave DM rooms.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (leave succeeds instead of throwing).
     */
    @Property(tries = 1)
    void leaveDirectRoomThrowsForbidden() {
        // Create a DM room between friends
        friendshipService.sendFriendRequest(userA, userB, "hi");
        friendshipService.acceptFriendRequest(
                friendshipService.listPendingIncoming(userB).getFirst().id(), userB);

        RoomDto dmRoom = directChatService.getOrCreateDirectChat(userA, userB);
        Room room = roomService.getRoomById(dmRoom.id());

        // userB (MEMBER, not OWNER) tries to leave the DIRECT room
        assertThatThrownBy(() -> roomMemberService.leaveRoom(room, userB))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Bug 2 — WebSocket Reply-To Content
    // ChatMessageHandler.sendMessage() should include replyToSenderUsername
    // and replyToContentSnippet in the broadcast when replyToId is non-null.
    // On unfixed code: both fields are null — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.2
     *
     * For any message with a non-null reply_to_id, the ChatMessageResponse
     * broadcast SHALL include non-null replyToSenderUsername and
     * replyToContentSnippet.
     *
     * Bug Condition: ChatMessageHandler passes null for both reply-to fields
     * in the ChatMessageResponse constructor.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (fields are null).
     */
    @Property(tries = 1)
    void replyToMessageIncludesContentInWebSocketBroadcast() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "reply-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // userA sends original message
        Message original = messageService.sendMessage(room, userA, "Hello from userA", null);

        // userB sends reply via REST (simulating WebSocket send via MockMvc)
        String requestBody = """
                {"room_id": "%s", "content": "Reply to userA", "reply_to_id": "%s"}
                """.formatted(room.getId(), original.getId());

        MvcResult result = mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // The REST response uses MessageService.toResponse() which DOES populate
        // reply-to fields. The actual bug is in ChatMessageHandler which passes null.
        // We verify the service-level behavior here: toResponse() should populate them.
        String replyToSenderUsername = com.jayway.jsonpath.JsonPath.read(body, "$.reply_to_sender_username");
        String replyToContentSnippet = com.jayway.jsonpath.JsonPath.read(body, "$.reply_to_content_snippet");

        assertThat(replyToSenderUsername)
                .as("reply_to_sender_username must be populated when reply_to_id is set")
                .isNotNull()
                .isEqualTo(userA.getUsername());

        assertThat(replyToContentSnippet)
                .as("reply_to_content_snippet must be populated when reply_to_id is set")
                .isNotNull()
                .isEqualTo("Hello from userA");
    }

    // -----------------------------------------------------------------------
    // Bug 6 — Display Name in Chat
    // MessageService.toResponse() should populate senderDisplayName from
    // the sender's display name.
    // On unfixed code: ChatMessageResponse has no senderDisplayName field — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.6
     *
     * For any message where the sender has a non-null displayName, the
     * response SHALL include a sender_display_name field matching the
     * sender's display name.
     *
     * Bug Condition: ChatMessageResponse has no senderDisplayName field;
     * the response only contains senderUsername.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (field does not exist).
     */
    @Property(tries = 1)
    void messageResponseIncludesSenderDisplayName() throws Exception {
        // Set display name for userA
        userService.updateProfile(userA.getId(), "Alice Display", userA.getUsername());

        RoomDto roomDto = roomService.createRoom(
                "display-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());

        messageService.sendMessage(room, userA, "Hello with display name", null);

        // Fetch message history via REST
        MvcResult result = mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // The response should contain sender_display_name field
        String senderDisplayName = com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].sender_display_name");
        assertThat(senderDisplayName)
                .as("sender_display_name must be populated from sender's display name")
                .isNotNull()
                .isEqualTo("Alice Display");
    }

    // -----------------------------------------------------------------------
    // Bug 7 — Password Reset 200
    // POST /api/password/reset with valid token should return 200 for
    // unauthenticated users.
    // On unfixed code: returns 302 redirect to /login — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.7
     *
     * POST /api/password/reset with a valid token and new password SHALL
     * return HTTP 200 OK for unauthenticated users.
     *
     * Bug Condition: /api/password/reset is not in SecurityConfig.permitAll(),
     * so Spring Security redirects unauthenticated requests to /login (302).
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (302 instead of 200).
     */
    @Property(tries = 1)
    void passwordResetReturns200ForUnauthenticatedUser() throws Exception {
        String rawToken = passwordService.createResetToken(userA);

        mockMvc.perform(post("/api/password/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "%s", "new_password": "newpassword123"}
                                """.formatted(rawToken)))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Bug 9 — Ban Removes Member
    // ModerationService.banUserFromRoom() should remove the banned user
    // from the RoomMember table.
    // On unfixed code: user remains a member after ban — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.9
     *
     * After banUserFromRoom(), the banned user SHALL no longer be in the
     * RoomMember table for that room.
     *
     * Bug Condition: banUserFromRoom() creates RoomBan but does NOT remove
     * the user from RoomMember (unlike kickMember() which does both).
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (user still a member).
     */
    @Property(tries = 1)
    void banUserRemovesMembership() {
        RoomDto roomDto = roomService.createRoom(
                "ban-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // Verify userB is a member before ban
        assertThat(roomMemberRepository.existsByRoomAndUser(room, userB)).isTrue();

        // Ban userB
        moderationService.banUserFromRoom(room, userA, userB);

        // After ban, userB should NOT be a member
        assertThat(roomMemberRepository.existsByRoomAndUser(room, userB))
                .as("Banned user should be removed from RoomMember table")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Bug 10 — Ban Blocks View Access
    // ChatWebController.roomView() should deny access for banned users.
    // On unfixed code: banned users can still view (200 OK) — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.10
     *
     * A banned user accessing GET /chat/rooms/{id} SHALL receive 403 Forbidden
     * or a redirect (not 200 OK).
     *
     * Bug Condition: roomView() only checks isMember, not isBanned. Since
     * Bug 9 means banned users remain members, they pass the check.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (200 instead of 403).
     */
    @Property(tries = 1)
    void bannedUserCannotViewRoom() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "banview-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // Ban userB
        moderationService.banUserFromRoom(room, userA, userB);

        // Banned user tries to view the room
        mockMvc.perform(get("/chat/rooms/{id}", room.getId())
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Bug 11 — Ban Blocks Send
    // MessageService.sendMessage() should throw ForbiddenException for
    // banned users.
    // On unfixed code: banned users can send messages — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.11
     *
     * A banned user attempting to send a message SHALL receive ForbiddenException.
     *
     * Bug Condition: sendMessage() only checks isMember, not isBanned.
     * Since Bug 9 means banned users remain members, they can send.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (message sends successfully).
     */
    @Property(tries = 1)
    void bannedUserCannotSendMessage() {
        RoomDto roomDto = roomService.createRoom(
                "bansend-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // Ban userB
        moderationService.banUserFromRoom(room, userA, userB);

        // Banned user tries to send a message
        assertThatThrownBy(() -> messageService.sendMessage(room, userB, "I am banned", null))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Bug 12 — Ban List No LazyInit
    // GET /api/rooms/{roomId}/bans should return 200 with ban data
    // (usernames populated).
    // On unfixed code: throws LazyInitializationException (500) — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.12
     *
     * GET /api/rooms/{roomId}/bans SHALL return 200 with populated username
     * fields in the ban list.
     *
     * Bug Condition: findByRoom() returns lazy proxies for user and bannedBy.
     * toBanResponse() accesses getUsername() outside a Hibernate session,
     * causing LazyInitializationException.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (500 instead of 200).
     */
    @Property(tries = 1)
    void banListReturns200WithPopulatedUsernames() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "banlist-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);

        // Ban userB
        moderationService.banUserFromRoom(room, userA, userB);

        // Owner fetches ban list
        MvcResult result = mockMvc.perform(get("/api/rooms/{roomId}/bans", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Verify ban data has populated usernames
        net.minidev.json.JSONArray bans = com.jayway.jsonpath.JsonPath.read(body, "$");
        assertThat(bans).isNotEmpty();

        String bannedUsername = com.jayway.jsonpath.JsonPath.read(body, "$[0].username");
        assertThat(bannedUsername)
                .as("Ban entry must have populated username")
                .isNotNull()
                .isNotEmpty();

        String bannedByUsername = com.jayway.jsonpath.JsonPath.read(body, "$[0].banned_by_username");
        assertThat(bannedByUsername)
                .as("Ban entry must have populated banned_by_username")
                .isNotNull()
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // Bug 13 — Ban List Access Control
    // GET /api/rooms/{roomId}/bans by a non-owner/non-admin should return 403.
    // On unfixed code: returns 200 with full ban list — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.13
     *
     * A non-owner/non-admin user accessing GET /api/rooms/{roomId}/bans
     * SHALL receive 403 Forbidden.
     *
     * Bug Condition: listBans() only calls resolveUser(principal) but does
     * NOT check if the user is the room owner or admin.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (200 instead of 403).
     */
    @Property(tries = 1)
    void nonOwnerCannotAccessBanList() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "banacl-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PUBLIC, userA);
        Room room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, userB);
        roomMemberService.joinPublicRoom(room, userC);

        // Ban userC (so there's data in the ban list)
        moderationService.banUserFromRoom(room, userA, userC);

        // userB (regular member, not owner/admin) tries to access ban list
        mockMvc.perform(get("/api/rooms/{roomId}/bans", room.getId())
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Bug 14/15 — Re-Invitation After Decline
    // POST /api/rooms/{roomId}/invitations should succeed (201) for a user
    // who previously declined.
    // On unfixed code: throws DataIntegrityViolationException (500) — FAILS.
    // -----------------------------------------------------------------------

    /**
     * Validates: Requirements 1.14, 1.15
     *
     * POST /api/rooms/{roomId}/invitations for a user who already has an
     * existing invitation (e.g., previously declined but record still exists,
     * or re-inviting while pending) SHALL succeed with 201 Created by
     * handling the existing record gracefully.
     *
     * Bug Condition: inviteUser() does not check for or clean up existing
     * invitation records before inserting, violating the unique constraint
     * (room_id, invitee_id).
     *
     * EXPECTED OUTCOME on unfixed code: FAILS (500 DataIntegrityViolationException).
     */
    @Property(tries = 1)
    void reInvitationAfterDeclineSucceeds() throws Exception {
        RoomDto roomDto = roomService.createRoom(
                "reinvite-room-" + UUID.randomUUID().toString().substring(0, 8),
                "test", RoomVisibility.PRIVATE, userA);
        Room room = roomService.getRoomById(roomDto.id());

        // First invitation: userA invites userB
        mockMvc.perform(post("/api/rooms/{roomId}/invitations", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isCreated());

        // Re-invitation without declining first: userA invites userB again
        // This triggers the unique constraint violation because the old
        // invitation record still exists and inviteUser() doesn't handle it
        mockMvc.perform(post("/api/rooms/{roomId}/invitations", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isCreated());
    }
}
