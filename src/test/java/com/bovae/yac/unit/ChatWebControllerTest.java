package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.web.ChatWebController;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

/**
 * Unit tests for {@link ChatWebController#roomView}.
 */
@ExtendWith(MockitoExtension.class)
class ChatWebControllerTest {

    private static final int INITIAL_PAGE_SIZE = 50;

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private MessageService messageService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoomBanRepository roomBanRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private ChatWebController controller;

    private User user;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        user = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .displayName("Alice")
                .passwordHash("hash")
                .build();
        when(principal.getName()).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    // --- access guards ---

    @Test
    void roomView_shouldThrowForbidden_whenUserIsBanned() {
        Room room = room(RoomVisibility.PUBLIC, "general");
        when(roomService.getRoomByIdWithOwner(roomId)).thenReturn(room);
        when(roomBanRepository.existsByRoomAndUser(room, user)).thenReturn(true);
        Model model = new ExtendedModelMap();

        assertThatThrownBy(() -> controller.roomView(roomId, model, principal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("banned");

        verifyNoInteractions(roomMemberService, messageService, notificationService);
    }

    @Test
    void roomView_shouldThrowForbidden_whenNotMemberAndRoomNotPublic() {
        Room room = room(RoomVisibility.PRIVATE, "secret");
        when(roomService.getRoomByIdWithOwner(roomId)).thenReturn(room);
        when(roomBanRepository.existsByRoomAndUser(room, user)).thenReturn(false);
        when(roomMemberService.isMember(room, user)).thenReturn(false);
        Model model = new ExtendedModelMap();

        assertThatThrownBy(() -> controller.roomView(roomId, model, principal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");

        verify(roomMemberService, never()).listMembers(any());
        verifyNoInteractions(messageService, notificationService);
    }

    // --- membership and read-marking ---

    @Test
    void roomView_shouldRenderRoomNameAndMarkRead_whenMemberOfPublicRoom() {
        Room room = room(RoomVisibility.PUBLIC, "general");
        when(roomService.getRoomByIdWithOwner(roomId)).thenReturn(room);
        when(roomBanRepository.existsByRoomAndUser(room, user)).thenReturn(false);
        when(roomMemberService.isMember(room, user)).thenReturn(true);
        // Current user is not the first member and has a different role: the role must be
        // resolved by matching the user id, not by taking the first member in the list.
        when(roomMemberService.listMembers(room))
                .thenReturn(List.of(
                        member(UUID.randomUUID(), "owner", "Owner", RoomRole.OWNER),
                        member(user.getId(), "alice", "Alice", RoomRole.MEMBER)));
        when(messageService.getMessageHistory(eq(room), isNull(), eq(INITIAL_PAGE_SIZE)))
                .thenReturn(emptyPage());
        Model model = new ExtendedModelMap();

        String view = controller.roomView(roomId, model, principal);

        assertThat(view).isEqualTo("chat/room");
        assertThat(model.getAttribute("displayName")).isEqualTo("general");
        assertThat(model.getAttribute("isMember")).isEqualTo(true);
        assertThat(model.getAttribute("currentUserRole")).isEqualTo(RoomRole.MEMBER);
        verify(notificationService).markRoomAsRead(user, room);
    }

    @Test
    void roomView_shouldNotMarkRead_whenNonMemberViewsPublicRoom() {
        Room room = room(RoomVisibility.PUBLIC, "general");
        when(roomService.getRoomByIdWithOwner(roomId)).thenReturn(room);
        when(roomBanRepository.existsByRoomAndUser(room, user)).thenReturn(false);
        when(roomMemberService.isMember(room, user)).thenReturn(false);
        when(roomMemberService.listMembers(room)).thenReturn(List.of());
        when(messageService.getMessageHistory(eq(room), isNull(), eq(INITIAL_PAGE_SIZE)))
                .thenReturn(emptyPage());
        Model model = new ExtendedModelMap();

        String view = controller.roomView(roomId, model, principal);

        assertThat(view).isEqualTo("chat/room");
        assertThat(model.getAttribute("isMember")).isEqualTo(false);
        assertThat(model.getAttribute("currentUserRole")).isNull();
        verify(notificationService, never()).markRoomAsRead(any(), any());
    }

    // --- direct-message display names ---

    @Test
    void roomView_shouldShowSavedMessages_whenDirectRoomIsSavedMessages() {
        Room room = room(RoomVisibility.DIRECT, "saved-messages-" + user.getId());
        stubDirectMemberRoom(room, List.of(member(user.getId(), "alice", "Alice", RoomRole.MEMBER)));
        Model model = new ExtendedModelMap();

        controller.roomView(roomId, model, principal);

        assertThat(model.getAttribute("displayName")).isEqualTo("Saved Messages");
    }

    @Test
    void roomView_shouldShowChatWithDisplayName_whenDirectCounterpartHasDisplayName() {
        Room room = room(RoomVisibility.DIRECT, "dm-" + UUID.randomUUID());
        RoomMemberDto self = member(user.getId(), "alice", "Alice", RoomRole.MEMBER);
        RoomMemberDto other = member(UUID.randomUUID(), "bob", "Bob", RoomRole.MEMBER);
        stubDirectMemberRoom(room, List.of(self, other));
        Model model = new ExtendedModelMap();

        controller.roomView(roomId, model, principal);

        assertThat(model.getAttribute("displayName")).isEqualTo("Chat with Bob");
    }

    @Test
    void roomView_shouldShowChatWithUsername_whenDirectCounterpartHasNoDisplayName() {
        Room room = room(RoomVisibility.DIRECT, "dm-" + UUID.randomUUID());
        RoomMemberDto self = member(user.getId(), "alice", "Alice", RoomRole.MEMBER);
        RoomMemberDto other = member(UUID.randomUUID(), "bob", null, RoomRole.MEMBER);
        stubDirectMemberRoom(room, List.of(self, other));
        Model model = new ExtendedModelMap();

        controller.roomView(roomId, model, principal);

        assertThat(model.getAttribute("displayName")).isEqualTo("Chat with bob");
    }

    @Test
    void roomView_shouldShowGenericDirectMessage_whenCounterpartGone() {
        Room room = room(RoomVisibility.DIRECT, "dm-" + UUID.randomUUID());
        stubDirectMemberRoom(room, List.of(member(user.getId(), "alice", "Alice", RoomRole.MEMBER)));
        Model model = new ExtendedModelMap();

        controller.roomView(roomId, model, principal);

        assertThat(model.getAttribute("displayName")).isEqualTo("Direct message");
    }

    // --- helpers ---

    private void stubDirectMemberRoom(Room room, List<RoomMemberDto> members) {
        when(roomService.getRoomByIdWithOwner(roomId)).thenReturn(room);
        when(roomBanRepository.existsByRoomAndUser(room, user)).thenReturn(false);
        when(roomMemberService.isMember(room, user)).thenReturn(true);
        when(roomMemberService.listMembers(room)).thenReturn(members);
        when(messageService.getMessageHistory(eq(room), isNull(), eq(INITIAL_PAGE_SIZE)))
                .thenReturn(emptyPage());
    }

    private Room room(RoomVisibility visibility, String name) {
        return Room.builder()
                .id(roomId)
                .name(name)
                .visibility(visibility)
                .owner(user)
                .build();
    }

    private RoomMemberDto member(UUID userId, String username, String displayName, RoomRole role) {
        return new RoomMemberDto(userId, username, displayName, role, Instant.now());
    }

    private MessagePage emptyPage() {
        return new MessagePage(List.of(), null, false);
    }
}
