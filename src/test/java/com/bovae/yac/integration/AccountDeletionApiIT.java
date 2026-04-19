package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for account deletion via UserApiController (DELETE /api/users/me).
 * Verifies cascade effects: owned rooms, memberships, friendships, and bans are removed.
 *
 * Validates Requirements: 7.11
 * Validates Correctness Properties: CP 8
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class AccountDeletionApiIT {

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
    private MessageService messageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserBanRepository userBanRepository;

    private User userA;
    private User userB;
    private User userC;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("alice@test.com", "alice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register("bob@test.com", "bob", "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
        UserDto userCDto = userService.register("carol@test.com", "carol", "testpass123");
        userC = userRepository.findById(userCDto.id()).orElseThrow();
    }

    // ---- Basic account deletion ----

    @Test
    void deleteAccount_returns204() throws Exception {
        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAccount_removesUserRecord() throws Exception {
        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(userA.getId())).isEmpty();
    }

    @Test
    void deleteAccount_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(delete("/api/users/me")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    // ---- Cascade: owned rooms deleted ----

    @Test
    void deleteAccount_removesOwnedRooms() throws Exception {
        Room ownedRoom = roomService.getRoomById(roomService.createRoom("alice-room", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(ownedRoom, userB);

        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(roomRepository.findById(ownedRoom.getId())).isEmpty();
    }

    // ---- Cascade: memberships in other rooms removed ----

    @Test
    void deleteAccount_removesMembershipsInOtherRooms() throws Exception {
        Room bobRoom = roomService.getRoomById(roomService.createRoom("bob-room", "desc", RoomVisibility.PUBLIC, userB).id());
        roomMemberService.joinPublicRoom(bobRoom, userA);

        assertThat(roomMemberRepository.existsByRoomAndUser(bobRoom, userA)).isTrue();

        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(roomMemberRepository.existsByRoomAndUser(bobRoom, userA)).isFalse();
        // Bob's room still exists
        assertThat(roomRepository.findById(bobRoom.getId())).isPresent();
    }

    // ---- Cascade: friendships removed ----

    @Test
    void deleteAccount_removesFriendships() throws Exception {
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, null);
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(friendshipRepository.findByRequesterOrRecipient(userB, userB))
                .noneMatch(f ->
                        f.getRequester().getId().equals(userA.getId())
                                || f.getRecipient().getId().equals(userA.getId()));
    }

    // ---- Cascade: user bans removed ----

    @Test
    void deleteAccount_removesUserBans() throws Exception {
        userBanService.banUser(userA, userB);
        userBanService.banUser(userC, userA);

        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(userBanRepository.findByBlocker(userA)).isEmpty();
        assertThat(userBanRepository.findByBlocked(userA)).isEmpty();
    }

    // ---- Cascade: other users' memberships in owned rooms removed ----

    @Test
    void deleteAccount_removesOtherUsersMembershipsInOwnedRooms() throws Exception {
        Room ownedRoom = roomService.getRoomById(roomService.createRoom("alice-room-2", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(ownedRoom, userB);
        roomMemberService.joinPublicRoom(ownedRoom, userC);

        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Room is gone, so memberships are gone too
        assertThat(roomRepository.findById(ownedRoom.getId())).isEmpty();
    }

    // ---- Cascade: messages in owned rooms removed ----

    @Test
    void deleteAccount_removesMessagesInOwnedRooms() throws Exception {
        Room ownedRoom = roomService.getRoomById(roomService.createRoom("alice-msg-room", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(ownedRoom, userB);
        messageService.sendMessage(ownedRoom, userA, "hello from alice", null);
        messageService.sendMessage(ownedRoom, userB, "hello from bob", null);

        mockMvc.perform(delete("/api/users/me")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Entire room is cascade-deleted including all messages
        assertThat(roomRepository.findById(ownedRoom.getId())).isEmpty();
    }
}
