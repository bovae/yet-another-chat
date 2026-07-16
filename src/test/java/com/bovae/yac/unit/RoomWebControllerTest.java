package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.web.RoomWebController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.RoomCatalogEntry;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

/**
 * Unit tests for {@link RoomWebController}.
 */
@ExtendWith(MockitoExtension.class)
class RoomWebControllerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private Principal principal;

    @InjectMocks
    private RoomWebController controller;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("hash")
                .build();
    }

    // --- catalog ---

    @Test
    void catalog_shouldPopulateModelAndReturnView_whenUserExists() {
        Page<RoomCatalogEntry> catalog = new PageImpl<>(List.of());
        Set<UUID> joined = Set.of(UUID.randomUUID());
        when(principal.getName()).thenReturn(user.getEmail());
        when(roomService.searchCatalog(eq("game"), any())).thenReturn(catalog);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(roomMemberRepository.findRoomIdsByUser(user)).thenReturn(joined);
        Model model = new ExtendedModelMap();

        String view = controller.catalog("game", 0, 20, principal, model);

        assertThat(view).isEqualTo("rooms/catalog");
        assertThat(model.getAttribute("catalog")).isEqualTo(catalog);
        assertThat(model.getAttribute("search")).isEqualTo("game");
        assertThat(model.getAttribute("joinedRoomIds")).isEqualTo(joined);
    }

    @Test
    void catalog_shouldThrowResourceNotFound_whenUserMissing() {
        when(principal.getName()).thenReturn("ghost@test.com");
        when(roomService.searchCatalog(eq(""), any())).thenReturn(new PageImpl<>(List.of()));
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
        Model model = new ExtendedModelMap();

        assertThatThrownBy(() -> controller.catalog("", 0, 20, principal, model))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");
    }

    // --- createForm ---

    @Test
    void createForm_shouldReturnCreateView() {
        assertThat(controller.createForm()).isEqualTo("rooms/create");
    }

    // --- joinRoom ---

    @Test
    void joinRoom_shouldJoinPublicRoomAndRedirect_whenUserExists() {
        UUID id = UUID.randomUUID();
        Room room = Room.builder()
                .id(id)
                .name("general")
                .visibility(RoomVisibility.PUBLIC)
                .owner(user)
                .build();
        when(principal.getName()).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(roomService.getRoomById(id)).thenReturn(room);

        String view = controller.joinRoom(id, principal);

        assertThat(view).isEqualTo("redirect:/chat/rooms/" + id);
        verify(roomMemberService).joinPublicRoom(room, user);
        // The banner join broadcasts so members see the joiner live (R5-03).
        verify(messageBroadcastService).broadcastMembership(room, user, "MEMBER_JOINED");
    }

    @Test
    void joinRoom_shouldThrowResourceNotFound_whenUserMissing() {
        UUID id = UUID.randomUUID();
        when(principal.getName()).thenReturn("ghost@test.com");
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.joinRoom(id, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");

        verifyNoInteractions(roomMemberService);
    }
}
