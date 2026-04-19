package com.bovae.yac.property;

import com.bovae.yac.controller.web.ChatWebController;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for room view clearing unread count.
 *
 * Property 7: Room view clears unread count
 *
 * For any authenticated user who is a member of a room, after the ChatWebController
 * renders the room view, the user's unread count for that room (as computed by
 * NotificationService.computeUnreadCount()) SHALL be 0.
 *
 * Validates: Requirements 8.1, 8.2, 8.3
 */
class RoomViewUnreadPropertyTest {

    private RoomService roomService;
    private RoomMemberService roomMemberService;
    private MessageService messageService;
    private NotificationService notificationService;
    private UserRepository userRepository;
    private ChatWebController controller;

    @BeforeTry
    void setUp() {
        roomService = mock(RoomService.class);
        roomMemberService = mock(RoomMemberService.class);
        messageService = mock(MessageService.class);
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        controller = new ChatWebController(
                roomService, roomMemberService, messageService,
                notificationService, userRepository
        );
    }

    @Provide
    Arbitrary<String> validEmails() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(12)
                .map(local -> local.toLowerCase() + "@example.com");
    }

    @Provide
    Arbitrary<String> validUsernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<Long> watermarks() {
        return Arbitraries.longs().between(1L, 10_000L);
    }

    @Provide
    Arbitrary<Integer> unreadCounts() {
        return Arbitraries.integers().between(0, 999);
    }

    @Provide
    Arbitrary<RoomVisibility> roomVisibilities() {
        return Arbitraries.of(RoomVisibility.values());
    }

    private User buildUser(String email, String username) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .username(username)
                .passwordHash("hashed")
                .build();
    }

    private Room buildRoom(RoomVisibility visibility, long nextWatermark) {
        return Room.builder()
                .id(UUID.randomUUID())
                .name("room-" + UUID.randomUUID().toString().substring(0, 8))
                .visibility(visibility)
                .nextWatermark(nextWatermark)
                .build();
    }

    private void stubControllerDependencies(Room room, User user, boolean isMember) {
        when(roomService.getRoomById(room.getId())).thenReturn(room);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(roomMemberService.isMember(room, user)).thenReturn(isMember);

        RoomMemberDto memberDto = new RoomMemberDto(
                user.getId(), user.getUsername(), null,
                RoomRole.MEMBER, Instant.now()
        );
        when(roomMemberService.listMembers(room)).thenReturn(
                isMember ? List.of(memberDto) : Collections.emptyList()
        );

        MessagePage emptyPage = new MessagePage(Collections.emptyList(), null, false);
        when(messageService.getMessageHistory(eq(room), eq(null), eq(50))).thenReturn(emptyPage);
    }

    /**
     * Property 7a: Member viewing room → markRoomAsRead is called
     *
     * For any authenticated user who is a member of a room, after the ChatWebController
     * renders the room view, notificationService.markRoomAsRead(user, room) SHALL be called.
     *
     * Validates: Requirements 8.1, 8.2
     */
    @Property(tries = 20)
    void memberViewingRoom_shallCallMarkRoomAsRead(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("watermarks") long nextWatermark,
            @ForAll("roomVisibilities") RoomVisibility visibility
    ) {
        User user = buildUser(email, username);
        Room room = buildRoom(visibility, nextWatermark);

        stubControllerDependencies(room, user, true);

        Principal principal = () -> email;
        Model model = new ConcurrentModel();

        controller.roomView(room.getId(), model, principal);

        verify(notificationService).markRoomAsRead(user, room);
    }

    /**
     * Property 7b: Non-member viewing room → markRoomAsRead is NOT called
     *
     * The application SHALL only call markRoomAsRead when the user is a member of the room.
     *
     * Validates: Requirements 8.3
     */
    @Property(tries = 20)
    void nonMemberViewingRoom_shallNotCallMarkRoomAsRead(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("watermarks") long nextWatermark,
            @ForAll("roomVisibilities") RoomVisibility visibility
    ) {
        User user = buildUser(email, username);
        Room room = buildRoom(visibility, nextWatermark);

        stubControllerDependencies(room, user, false);

        Principal principal = () -> email;
        Model model = new ConcurrentModel();

        controller.roomView(room.getId(), model, principal);

        verify(notificationService, never()).markRoomAsRead(any(User.class), any(Room.class));
    }
}
