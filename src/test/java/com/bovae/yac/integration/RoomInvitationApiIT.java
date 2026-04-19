package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for RoomInvitationApiController: invite user to private room,
 * accept invitation, and non-invited user join rejection.
 *
 * Validates Requirements: 7.7
 * Validates Correctness Properties: CP 14
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class RoomInvitationApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private UserRepository userRepository;

    private User owner;
    private User invitee;
    private User outsider;
    private Room privateRoom;

    @BeforeEach
    void setUp() {
        UserDto ownerDto = userService.register("owner@test.com", "owner", "testpass123");
        owner = userRepository.findById(ownerDto.id()).orElseThrow();
        UserDto inviteeDto = userService.register("invitee@test.com", "invitee", "testpass123");
        invitee = userRepository.findById(inviteeDto.id()).orElseThrow();
        UserDto outsiderDto = userService.register("outsider@test.com", "outsider", "testpass123");
        outsider = userRepository.findById(outsiderDto.id()).orElseThrow();
        privateRoom = roomService.getRoomById(roomService.createRoom("private-room", "A private room", RoomVisibility.PRIVATE, owner).id());
    }

    // ---- Invite user to private room ----

    @Test
    void inviteUser_byOwner_returns201() throws Exception {
        mockMvc.perform(post("/api/rooms/{roomId}/invitations", privateRoom.getId())
                        .with(user(owner.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": "%s"}
                                """.formatted(invitee.getId())))
                .andExpect(status().isCreated());
    }

    // ---- Accept invitation ----

    @Test
    void acceptInvitation_byInvitee_returns200() throws Exception {
        RoomInvitation invitation = roomInvitationRepository.save(
                RoomInvitation.builder()
                        .room(privateRoom)
                        .inviter(owner)
                        .invitee(invitee)
                        .build());

        mockMvc.perform(post("/api/rooms/{roomId}/invitations/{id}/accept",
                        privateRoom.getId(), invitation.getId())
                        .with(user(invitee.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void acceptInvitation_byNonInvitee_returns404() throws Exception {
        RoomInvitation invitation = roomInvitationRepository.save(
                RoomInvitation.builder()
                        .room(privateRoom)
                        .inviter(owner)
                        .invitee(invitee)
                        .build());

        // outsider tries to accept invitee's invitation
        mockMvc.perform(post("/api/rooms/{roomId}/invitations/{id}/accept",
                        privateRoom.getId(), invitation.getId())
                        .with(user(outsider.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---- Non-invited user join rejection ----

    @Test
    void joinPrivateRoom_withoutInvitation_returns403() throws Exception {
        // outsider tries to join private room via the public join endpoint
        mockMvc.perform(post("/api/rooms/{id}/join", privateRoom.getId())
                        .with(user(outsider.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ---- Decline / cancel invitation ----

    @Test
    void declineInvitation_returns204() throws Exception {
        RoomInvitation invitation = roomInvitationRepository.save(
                RoomInvitation.builder()
                        .room(privateRoom)
                        .inviter(owner)
                        .invitee(invitee)
                        .build());

        mockMvc.perform(delete("/api/rooms/{roomId}/invitations/{id}",
                        privateRoom.getId(), invitation.getId())
                        .with(user(invitee.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }
}
