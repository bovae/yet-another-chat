package com.bovae.yac.integration;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the navbar aggregate summary endpoint (R3-10):
 * {@code GET /api/notifications/summary}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class NotificationSummaryApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private UserRepository userRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        UserDto aliceDto = userService.register("alice@test.com", "alice", "testpass123");
        alice = userRepository.findById(aliceDto.id()).orElseThrow();
        UserDto bobDto = userService.register("bob@test.com", "bob", "testpass123");
        bob = userRepository.findById(bobDto.id()).orElseThrow();
    }

    @Test
    void summary_reportsUnreadFriendRequestAndInvitationCounts() throws Exception {
        // One unread message for bob: he joins a public room, then alice posts.
        Room room = roomService.getRoomById(roomService
                .createRoom("summary-room", "desc", RoomVisibility.PUBLIC, alice)
                .id());
        roomMemberService.joinPublicRoom(room, bob);
        messageService.sendMessage(room, alice, "hello bob", null);

        // One pending friend request for bob.
        friendshipService.sendFriendRequest(alice, bob, null);

        // One pending room invitation for bob.
        Room privateRoom = roomService.getRoomById(roomService
                .createRoom("secret-room", "desc", RoomVisibility.PRIVATE, alice)
                .id());
        roomInvitationRepository.save(RoomInvitation.builder()
                .room(privateRoom)
                .inviter(alice)
                .invitee(bob)
                .build());

        mockMvc.perform(get("/api/notifications/summary")
                        .with(user(bob.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_total", is(1)))
                .andExpect(jsonPath("$.pending_friend_requests", is(1)))
                .andExpect(jsonPath("$.pending_invitations", is(1)));
    }

    @Test
    void summary_freshUser_reportsAllZero() throws Exception {
        mockMvc.perform(get("/api/notifications/summary")
                        .with(user(bob.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_total", is(0)))
                .andExpect(jsonPath("$.pending_friend_requests", is(0)))
                .andExpect(jsonPath("$.pending_invitations", is(0)));
    }
}
