package com.bovae.yac.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
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
 * Integration tests for moderation flows via RoomMemberApiController and RoomBanApiController:
 * kick member, ban/unban from room, grant/revoke admin role, admin message deletion.
 *
 * Validates Requirements: 7.6
 * Validates Correctness Properties: CP 16
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class ModerationApiIT {

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
    private UserRepository userRepository;

    private User owner;
    private User member;
    private User outsider;
    private Room room;

    @BeforeEach
    void setUp() {
        UserDto ownerDto = userService.register("owner@test.com", "owner", "testpass123");
        owner = userRepository.findById(ownerDto.id()).orElseThrow();
        UserDto memberDto = userService.register("member@test.com", "member", "testpass123");
        member = userRepository.findById(memberDto.id()).orElseThrow();
        UserDto outsiderDto = userService.register("outsider@test.com", "outsider", "testpass123");
        outsider = userRepository.findById(outsiderDto.id()).orElseThrow();
        room = roomService.getRoomById(roomService
                .createRoom("mod-room", "A moderation test room", RoomVisibility.PUBLIC, owner)
                .id());
        roomMemberService.joinPublicRoom(room, member);
    }

    // ---- Kick member ----

    @Test
    void kickMember_byOwner_returns204() throws Exception {
        mockMvc.perform(delete("/api/rooms/{roomId}/members/{userId}", room.getId(), member.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify member no longer in member list
        mockMvc.perform(get("/api/rooms/{roomId}/members", room.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void kickMember_ownerCannotBeKicked_returns403() throws Exception {
        // Grant member admin role so they can attempt moderation
        moderationService.grantAdminRole(room, owner, member);

        mockMvc.perform(delete("/api/rooms/{roomId}/members/{userId}", room.getId(), owner.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void kickMember_byNonAdmin_returns403() throws Exception {
        mockMvc.perform(delete("/api/rooms/{roomId}/members/{userId}", room.getId(), outsider.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Ban user from room ----

    @Test
    void banUserFromRoom_byOwner_returns201() throws Exception {
        mockMvc.perform(post("/api/rooms/{roomId}/bans", room.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(member.getId())))
                .andExpect(status().isCreated());

        // Verify ban appears in ban list
        mockMvc.perform(get("/api/rooms/{roomId}/bans", room.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void banUserFromRoom_byNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/rooms/{roomId}/bans", room.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(outsider.getId())))
                .andExpect(status().isForbidden());
    }

    // ---- Unban user from room ----

    @Test
    void unbanUserFromRoom_byOwner_returns204() throws Exception {
        moderationService.banUserFromRoom(room, owner, outsider);

        mockMvc.perform(delete("/api/rooms/{roomId}/bans/{userId}", room.getId(), outsider.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify ban list is now empty
        mockMvc.perform(get("/api/rooms/{roomId}/bans", room.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unbanUserFromRoom_noBanExists_returns404() throws Exception {
        mockMvc.perform(delete("/api/rooms/{roomId}/bans/{userId}", room.getId(), outsider.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---- Grant admin role ----

    @Test
    void grantAdminRole_byOwner_returns200() throws Exception {
        mockMvc.perform(put("/api/rooms/{roomId}/members/{userId}/role", room.getId(), member.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "ADMIN"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void grantAdminRole_byNonOwner_returns403() throws Exception {
        // member is not owner, so cannot grant admin
        mockMvc.perform(put("/api/rooms/{roomId}/members/{userId}/role", room.getId(), outsider.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "ADMIN"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ---- Revoke admin role ----

    @Test
    void revokeAdminRole_byOwner_returns200() throws Exception {
        moderationService.grantAdminRole(room, owner, member);

        mockMvc.perform(put("/api/rooms/{roomId}/members/{userId}/role", room.getId(), member.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MEMBER"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void revokeAdminRole_onOwner_returns403() throws Exception {
        mockMvc.perform(put("/api/rooms/{roomId}/members/{userId}/role", room.getId(), owner.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "MEMBER"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ---- Admin message deletion ----

    @Test
    void deleteMessage_byAdmin_returns204() throws Exception {
        Message msg = messageService.sendMessage(room, member, "To be moderated", null);
        moderationService.grantAdminRole(room, owner, member);

        // Create a third user as admin to delete member's message
        UserDto adminDto = userService.register("admin@test.com", "admin", "testpass123");
        User admin = userRepository.findById(adminDto.id()).orElseThrow();
        roomMemberService.joinPublicRoom(room, admin);
        moderationService.grantAdminRole(room, owner, admin);

        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(admin.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify message no longer appears in history
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(0)));
    }

    @Test
    void deleteMessage_byOwner_returns204() throws Exception {
        Message msg = messageService.sendMessage(room, member, "Owner will delete this", null);

        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify message no longer appears in history
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(owner.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(0)));
    }

    @Test
    void deleteMessage_byNonAdminNonAuthor_returns403() throws Exception {
        Message msg = messageService.sendMessage(room, owner, "Protected message", null);

        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(member.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
