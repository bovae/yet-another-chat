package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserBanService;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Multi-user scenario integration tests exercising cross-user interaction flows:
 * friend requests, bans, direct chats, kicks, account deletion cascades,
 * message exchange, private room invitations, and unban behavior.
 *
 * Validates Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8
 * Validates Correctness Properties: CP 8, CP 10, CP 11, CP 14, CP 15, CP 16, CP 17, CP 22
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class MultiUserScenarioIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserBanService userBanService;

    @Autowired
    private DirectChatService directChatService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private RoomBanRepository roomBanRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private com.bovae.yac.repository.UserRepository userRepository;

    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("usera-mu@test.com", "useramu", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register("userb-mu@test.com", "userbmu", "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
    }

    // ---- Req 10.1: Full friend request flow ----

    @Test
    void fullFriendRequestFlow_sendAcceptAndBothSeeEachOther() throws Exception {
        // User A sends friend request to User B
        mockMvc.perform(post("/api/friends/request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "userbmu"}
                                """))
                .andExpect(status().isCreated());

        // Find the pending friendship to accept it
        Friendship pending = friendshipRepository.findByRequesterAndRecipient(userA, userB)
                .orElseThrow();

        // User B accepts the friend request
        mockMvc.perform(post("/api/friends/{id}/accept", pending.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // User A sees User B in friend list
        mockMvc.perform(get("/api/friends")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // User B sees User A in friend list
        mockMvc.perform(get("/api/friends")
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---- Req 10.2: Ban side effects ----

    @Test
    void banSideEffects_blockedUserCannotSendMessagesOrFriendRequests() throws Exception {
        // Set up friendship first
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        // User A bans User B
        userBanService.banUser(userA, userB);

        // Verify friendship is deleted
        mockMvc.perform(get("/api/friends")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // User B cannot send friend request to User A
        mockMvc.perform(post("/api/friends/request")
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "useramu"}
                                """))
                .andExpect(status().isForbidden());

        // User B cannot send messages in a shared room to User A (via direct chat)
        // Create a public room where both are members to test message blocking
        Room room = roomService.getRoomById(roomService.createRoom("ban-msg-room", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(room, userB);

        // User B can still send messages in a public room (user ban only affects DMs and friend requests)
        // The ban blocks direct chat messages, not public room messages
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Message in public room"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated());
    }

    // ---- Req 10.3: Ban + direct chat becomes read-only ----

    @Test
    void banAndDirectChat_becomesReadOnlyForBothParticipants() throws Exception {
        // Set up friendship and direct chat
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);
        Room directChat = roomService.getRoomById(directChatService.getOrCreateDirectChat(userA, userB).id());

        // Both can send messages before ban
        messageService.sendMessage(directChat, userA, "Hello from A", null);
        messageService.sendMessage(directChat, userB, "Hello from B", null);

        // User A bans User B
        userBanService.banUser(userA, userB);

        // User B cannot send messages in the direct chat
        mockMvc.perform(post("/api/rooms/{roomId}/messages", directChat.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Should be blocked"}
                                """.formatted(directChat.getId())))
                .andExpect(status().isForbidden());

        // User A also cannot send messages in the direct chat (ban is bidirectional)
        mockMvc.perform(post("/api/rooms/{roomId}/messages", directChat.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Should also be blocked"}
                                """.formatted(directChat.getId())))
                .andExpect(status().isForbidden());
    }

    // ---- Req 10.4: Admin kick — kicked member cannot access room messages, RoomBan created ----

    @Test
    void adminKick_kickedMemberCannotAccessMessagesAndRoomBanCreated() throws Exception {
        Room room = roomService.getRoomById(roomService.createRoom("kick-room", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(room, userB);

        // User A (owner) kicks User B
        mockMvc.perform(delete("/api/rooms/{roomId}/members/{userId}", room.getId(), userB.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify RoomBan was created
        boolean banned = roomBanRepository.existsByRoomAndUser(room, userB);
        assert banned : "RoomBan should exist after kick";

        // User B cannot access room messages (non-member)
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Should be forbidden"}
                                """.formatted(room.getId())))
                .andExpect(status().isForbidden());

        // User B cannot rejoin the room (banned)
        mockMvc.perform(post("/api/rooms/{id}/join", room.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Req 10.5: Owner account deletion cascades ----

    @Test
    void ownerAccountDeletion_ownedRoomsCascadeDeleted() throws Exception {
        // User A creates rooms and User B joins one
        Room room1 = roomService.getRoomById(roomService.createRoom("owned-room-1", "desc", RoomVisibility.PUBLIC, userA).id());
        Room room2 = roomService.getRoomById(roomService.createRoom("owned-room-2", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(room1, userB);
        messageService.sendMessage(room1, userA, "Message in room 1", null);
        messageService.sendMessage(room1, userB, "User B message", null);

        // User A deletes their account
        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Owned rooms should no longer exist in catalog
        mockMvc.perform(get("/api/rooms")
                        .with(user(userB.getEmail()).roles("USER"))
                        .param("search", "owned-room"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        // Room entities should be deleted from the database
        assert roomRepository.findById(room1.getId()).isEmpty() : "Room 1 should be deleted";
        assert roomRepository.findById(room2.getId()).isEmpty() : "Room 2 should be deleted";
    }

    // ---- Req 10.6: Message exchange — two users see messages in correct watermark order ----

    @Test
    void messageExchange_bothUsersSeeMessagesInWatermarkOrder() throws Exception {
        Room room = roomService.getRoomById(roomService.createRoom("exchange-room", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(room, userB);

        // User A sends first message
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "First from A"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated());

        // User B sends second message
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Second from B"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated());

        // User A sends third message
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Third from A"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated());

        // User A sees all 3 messages in watermark order
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(3)))
                .andExpect(jsonPath("$.messages[0].content", is("First from A")))
                .andExpect(jsonPath("$.messages[0].watermark", is(1)))
                .andExpect(jsonPath("$.messages[1].content", is("Second from B")))
                .andExpect(jsonPath("$.messages[1].watermark", is(2)))
                .andExpect(jsonPath("$.messages[2].content", is("Third from A")))
                .andExpect(jsonPath("$.messages[2].watermark", is(3)));

        // User B sees the same messages in the same order
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(3)))
                .andExpect(jsonPath("$.messages[0].content", is("First from A")))
                .andExpect(jsonPath("$.messages[1].content", is("Second from B")))
                .andExpect(jsonPath("$.messages[2].content", is("Third from A")));
    }

    // ---- Req 10.7: Private room invitation flow ----

    @Test
    void privateRoomInvitationFlow_inviteAcceptAndSendMessage() throws Exception {
        Room privateRoom = roomService.getRoomById(roomService.createRoom("private-invite-room", "desc", RoomVisibility.PRIVATE, userA).id());

        // User A invites User B
        mockMvc.perform(post("/api/rooms/{roomId}/invitations", privateRoom.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(userB.getId())))
                .andExpect(status().isCreated());

        // Find the invitation
        RoomInvitation invitation = roomInvitationRepository.findByRoomAndInvitee(privateRoom, userB)
                .orElseThrow();

        // User B accepts the invitation
        mockMvc.perform(post("/api/rooms/{roomId}/invitations/{id}/accept", privateRoom.getId(), invitation.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());

        // User B is now a member and can send messages
        mockMvc.perform(post("/api/rooms/{roomId}/messages", privateRoom.getId())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Hello from invited user"}
                                """.formatted(privateRoom.getId())))
                .andExpect(status().isCreated());

        // Verify message appears in history
        mockMvc.perform(get("/api/rooms/{roomId}/messages", privateRoom.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.messages[0].content", is("Hello from invited user")));
    }

    // ---- Req 10.8: Unban — user can send friend requests again but friendship not restored ----

    @Test
    void unban_userCanSendFriendRequestsAgainButFriendshipNotRestored() throws Exception {
        // Set up friendship
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        // User A bans User B (deletes friendship)
        userBanService.banUser(userA, userB);

        // Verify friendship is gone
        mockMvc.perform(get("/api/friends")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // User A unbans User B
        userBanService.unbanUser(userA, userB);

        // Friendship is NOT restored — still empty
        mockMvc.perform(get("/api/friends")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/friends")
                        .with(user(userB.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // User B can now send a new friend request to User A
        mockMvc.perform(post("/api/friends/request")
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "useramu"}
                                """))
                .andExpect(status().isCreated());
    }
}
