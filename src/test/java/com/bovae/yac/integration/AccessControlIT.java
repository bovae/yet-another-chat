package com.bovae.yac.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.FileStorageService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.ModerationService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for access control and authorization enforcement across all API controllers.
 * Verifies that non-members, non-admins, non-owners, banned users, blocked users,
 * and unauthenticated users are properly denied access.
 *
 * Validates Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11, 9.12, 14.11
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class AccessControlIT {

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
    private MessageService messageService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserBanService userBanService;

    @Autowired
    private DirectChatService directChatService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private UserRepository userRepository;

    private User owner;
    private User member;
    private User outsider;
    private Room room;

    @BeforeEach
    void setUp() {
        UserDto ownerDto = userService.register("owner-ac@test.com", "ownerac", "testpass123");
        owner = userRepository.findById(ownerDto.id()).orElseThrow();
        UserDto memberDto = userService.register("member-ac@test.com", "memberac", "testpass123");
        member = userRepository.findById(memberDto.id()).orElseThrow();
        UserDto outsiderDto = userService.register("outsider-ac@test.com", "outsiderac", "testpass123");
        outsider = userRepository.findById(outsiderDto.id()).orElseThrow();
        RoomDto roomDto =
                roomService.createRoom("ac-test-room", "Access control test room", RoomVisibility.PUBLIC, owner);
        room = roomService.getRoomById(roomDto.id());
        roomMemberService.joinPublicRoom(room, member);
    }

    // ---- Req 9.1: Non-member sending message to room receives 403 ----

    @Test
    void nonMember_sendMessage_returns403() throws Exception {
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(outsider.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "I should not be able to send this"}
                                """.formatted(room.getId())))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.2: Non-member downloading attachment receives 403 ----

    @Test
    void nonMember_downloadAttachment_returns403() throws Exception {
        // Public rooms are readable by non-members, so an IDOR test needs a PRIVATE room (R1-15).
        Room privateRoom = roomService.getRoomById(roomService
                .createRoom("ac-private", "private", RoomVisibility.PRIVATE, owner)
                .id());
        Message msg = messageService.sendMessage(privateRoom, owner, "file msg", null);
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "test content".getBytes());
        Attachment attachment = fileStorageService.uploadFile(file, msg, privateRoom, owner, null);

        // Outsider (non-member of the private room) tries to download
        mockMvc.perform(get("/api/rooms/{roomId}/attachments/{id}/download", privateRoom.getId(), attachment.getId())
                        .with(user(outsider.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.3: Non-admin attempting kick receives 403 ----

    @Test
    void nonAdmin_kickMember_returns403() throws Exception {
        // member (MEMBER role) tries to kick outsider — but outsider isn't even a member.
        // Let's add outsider to the room, then have member (non-admin) try to kick outsider.
        roomMemberService.joinPublicRoom(room, outsider);

        mockMvc.perform(delete("/api/rooms/{roomId}/members/{userId}", room.getId(), outsider.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.4: Non-admin attempting message deletion receives 403 ----

    @Test
    void nonAdmin_deleteOtherUsersMessage_returns403() throws Exception {
        Message msg = messageService.sendMessage(room, owner, "Owner's message", null);

        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.5: Non-owner attempting admin grant receives 403 ----

    @Test
    void nonOwner_grantAdminRole_returns403() throws Exception {
        roomMemberService.joinPublicRoom(room, outsider);

        mockMvc.perform(put("/api/rooms/{roomId}/members/{userId}/role", room.getId(), outsider.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "ADMIN"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.6: User editing another user's message receives 403 ----

    @Test
    void user_editAnotherUsersMessage_returns403() throws Exception {
        Message msg = messageService.sendMessage(room, owner, "Owner wrote this", null);

        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Trying to edit someone else's message"}
                                """.formatted(room.getId())))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.7: User deleting another user's message (without admin role) receives 403 ----

    @Test
    void user_deleteAnotherUsersMessage_withoutAdminRole_returns403() throws Exception {
        Message msg = messageService.sendMessage(room, owner, "Protected message", null);

        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.8: Banned user attempting room join receives 403 ----

    @Test
    void bannedUser_joinRoom_returns403() throws Exception {
        // Ban outsider from the room
        moderationService.banUserFromRoom(room, owner, outsider);

        mockMvc.perform(post("/api/rooms/{id}/join", room.getId())
                        .with(user(outsider.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.9: Blocked user sending friend request receives 403 ----

    @Test
    void blockedUser_sendFriendRequest_returns403() throws Exception {
        // Owner blocks outsider
        userBanService.banUser(owner, outsider);

        mockMvc.perform(post("/api/friends/request")
                        .with(user(outsider.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "ownerac"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.10: Blocked user sending message in frozen direct chat receives 403 ----

    @Test
    void blockedUser_sendMessageInFrozenDirectChat_returns403() throws Exception {
        // Set up friendship and direct chat between owner and outsider
        Friendship friendship = friendshipService.sendFriendRequest(owner, outsider, null);
        friendshipService.acceptFriendRequest(friendship.getId(), outsider);
        RoomDto directChatDto = directChatService.getOrCreateDirectChat(owner, outsider);
        Room directChat = roomService.getRoomById(directChatDto.id());

        // Owner blocks outsider — direct chat becomes frozen
        userBanService.banUser(owner, outsider);

        // Outsider tries to send a message in the frozen direct chat
        mockMvc.perform(post("/api/rooms/{roomId}/messages", directChat.getId())
                        .with(user(outsider.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Should not be allowed"}
                                """.formatted(directChat.getId())))
                .andExpect(status().isForbidden());
    }

    // ---- Req 9.11: Unauthenticated request to /api/** receives 401 (or 3xx redirect for form login) ----

    @Test
    void unauthenticated_apiRequest_isRejected() throws Exception {
        // Spring Security with form login redirects unauthenticated requests to /login
        mockMvc.perform(get("/api/rooms")).andExpect(status().is3xxRedirection());
    }

    @Test
    void unauthenticated_postApiRequest_isRejected() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "unauth-room", "description": "desc", "visibility": "PUBLIC"}
                                """))
                .andExpect(status().is3xxRedirection());
    }

    // ---- Req 9.12: Non-invited user joining private room receives 403 ----

    @Test
    void nonInvitedUser_joinPrivateRoom_returns403() throws Exception {
        RoomDto privateRoomDto =
                roomService.createRoom("private-ac-room", "Private room", RoomVisibility.PRIVATE, owner);
        Room privateRoom = roomService.getRoomById(privateRoomDto.id());

        // Outsider tries to join the private room without an invitation
        // joinPublicRoom rejects non-PUBLIC rooms with ForbiddenException
        mockMvc.perform(post("/api/rooms/{id}/join", privateRoom.getId())
                        .with(user(outsider.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Req 14.11: CSRF tokens required for POST/PUT/DELETE to /api/** ----

    @Test
    void postWithoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .with(user(owner.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "csrf-test-room", "description": "desc", "visibility": "PUBLIC"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void putWithoutCsrf_returns403() throws Exception {
        Message msg = messageService.sendMessage(room, owner, "CSRF test msg", null);

        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Edited without CSRF"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteWithoutCsrf_returns403() throws Exception {
        Message msg = messageService.sendMessage(room, owner, "CSRF delete test", null);

        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
    }
}
