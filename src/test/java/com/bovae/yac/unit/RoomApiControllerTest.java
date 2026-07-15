package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.RoomApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.CreateRoomRequest;
import com.bovae.yac.model.dto.MyRoomEntry;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UpdateRoomRequest;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link RoomApiController}. */
@ExtendWith(MockitoExtension.class)
class RoomApiControllerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private RoomInvitationRepository roomInvitationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private RoomApiController controller;

    private User user;
    private Room room;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        user = User.builder()
                .id(UUID.randomUUID())
                .email("user@test.com")
                .username("testuser")
                .displayName("Test User")
                .passwordHash("hashed")
                .build();
        room = Room.builder()
                .id(roomId)
                .name("test-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(user)
                .build();

        lenient().when(principal.getName()).thenReturn(user.getEmail());
        lenient().when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    // --- createRoom ---

    @Test
    void createRoom_shouldReturnBadRequest_whenVisibilityIsDirect() {
        CreateRoomRequest request = new CreateRoomRequest("name", "desc", RoomVisibility.DIRECT);

        ResponseEntity<RoomDto> response = controller.createRoom(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNull();
        verify(roomService, never()).createRoom("name", "desc", RoomVisibility.DIRECT, user);
    }

    @Test
    void createRoom_shouldReturnCreated_whenVisibilityIsPublic() {
        CreateRoomRequest request = new CreateRoomRequest("name", "desc", RoomVisibility.PUBLIC);
        RoomDto dto = roomDto();
        when(roomService.createRoom("name", "desc", RoomVisibility.PUBLIC, user))
                .thenReturn(dto);

        ResponseEntity<RoomDto> response = controller.createRoom(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(dto);
    }

    // --- updateRoom ---

    @Test
    void updateRoom_shouldReturnOkWithUpdatedRoom_whenRequestValid() {
        UpdateRoomRequest request = new UpdateRoomRequest("new-name", "new-desc", RoomVisibility.PRIVATE);
        RoomDto dto = roomDto();
        when(roomService.updateRoom(roomId, user, "new-name", "new-desc", RoomVisibility.PRIVATE))
                .thenReturn(dto);

        ResponseEntity<RoomDto> response = controller.updateRoom(roomId, request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(dto);
    }

    // --- myRooms ---

    @Test
    void myRooms_shouldReturnUserRoomList_whenPrincipalResolves() {
        MyRoomEntry entry = new MyRoomEntry(roomId, "test-room", RoomVisibility.PUBLIC, 3, null, null);
        when(roomService.listUserRoomsWithUnread(user)).thenReturn(List.of(entry));

        ResponseEntity<List<MyRoomEntry>> response = controller.myRooms(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(entry);
    }

    // --- markRead ---

    @Test
    void markRead_shouldReturnNoContentAndMarkRoom_whenPrincipalResolves() {
        when(roomService.getRoomById(roomId)).thenReturn(room);

        ResponseEntity<Void> response = controller.markRead(roomId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(notificationService).markRoomAsRead(user, room);
    }

    // --- resolveUser ---

    @Test
    void myRooms_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.myRooms(principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(roomService, never()).listUserRoomsWithUnread(user);
    }

    private RoomDto roomDto() {
        return new RoomDto(
                roomId,
                "test-room",
                "desc",
                RoomVisibility.PUBLIC,
                user.getId(),
                user.getUsername(),
                1L,
                Instant.now());
    }
}
