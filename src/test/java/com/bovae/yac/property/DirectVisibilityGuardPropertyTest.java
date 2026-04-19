package com.bovae.yac.property;

import com.bovae.yac.controller.api.RoomApiController;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.CreateRoomRequest;
import com.bovae.yac.model.dto.RoomMapper;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for DIRECT visibility immutability.
 *
 * Validates: Requirements 5.1, 5.2, 5.4, 15.1
 */
class DirectVisibilityGuardPropertyTest {

    private RoomRepository roomRepository;
    private RoomService roomService;
    private RoomApiController roomApiController;

    @BeforeTry
    void setUp() {
        roomRepository = mock(RoomRepository.class);
        RoomMemberRepository roomMemberRepository = mock(RoomMemberRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        RoomBanRepository roomBanRepository = mock(RoomBanRepository.class);
        RoomInvitationRepository roomInvitationRepository = mock(RoomInvitationRepository.class);
        UnreadMarkerRepository unreadMarkerRepository = mock(UnreadMarkerRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        RoomMapper roomMapper = mock(RoomMapper.class);

        roomService = new RoomService(
                roomRepository,
                roomMemberRepository,
                messageRepository,
                attachmentRepository,
                roomBanRepository,
                roomInvitationRepository,
                unreadMarkerRepository,
                notificationService,
                roomMapper
        );

        UserRepository userRepository = mock(UserRepository.class);
        RoomMemberService roomMemberService = mock(RoomMemberService.class);

        roomApiController = new RoomApiController(
                roomService,
                roomMemberService,
                roomInvitationRepository,
                userRepository
        );
    }

    @Provide
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(0)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<RoomVisibility> nonDirectVisibilities() {
        return Arbitraries.of(RoomVisibility.PUBLIC, RoomVisibility.PRIVATE);
    }

    @Provide
    Arbitrary<RoomVisibility> allVisibilities() {
        return Arbitraries.of(RoomVisibility.PUBLIC, RoomVisibility.PRIVATE, RoomVisibility.DIRECT);
    }

    private User buildUser(String username) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(username + "@test.com")
                .username(username)
                .passwordHash("hashed")
                .build();
    }

    private Room buildRoom(String name, RoomVisibility visibility, User owner) {
        return Room.builder()
                .id(UUID.randomUUID())
                .name(name)
                .visibility(visibility)
                .owner(owner)
                .nextWatermark(1L)
                .build();
    }

    /**
     * Property 4a: Updating a DIRECT room with any fields → ForbiddenException
     *
     * For any room with visibility == DIRECT, calling updateRoom() with any combination
     * of fields SHALL throw ForbiddenException.
     *
     * Validates: Requirements 5.1, 5.2
     */
    @Property(tries = 20)
    void updatingDirectRoom_withAnyFields_shallThrowForbidden(
            @ForAll("roomNames") String roomName,
            @ForAll("descriptions") String newName,
            @ForAll("descriptions") String newDescription,
            @ForAll("allVisibilities") RoomVisibility newVisibility
    ) {
        User owner = buildUser("owner");
        Room directRoom = buildRoom(roomName, RoomVisibility.DIRECT, owner);

        when(roomRepository.findById(directRoom.getId())).thenReturn(Optional.of(directRoom));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> roomService.updateRoom(directRoom.getId(), owner, newName, newDescription, newVisibility));

        assertThat(ex.getMessage()).contains("DIRECT rooms cannot be modified");
    }

    /**
     * Property 4b: Setting any room's visibility to DIRECT → ForbiddenException
     *
     * For any room with visibility != DIRECT, calling updateRoom() with visibility = DIRECT
     * SHALL throw ForbiddenException.
     *
     * Validates: Requirements 5.2
     */
    @Property(tries = 20)
    void settingVisibilityToDirect_onNonDirectRoom_shallThrowForbidden(
            @ForAll("roomNames") String roomName,
            @ForAll("nonDirectVisibilities") RoomVisibility originalVisibility,
            @ForAll("descriptions") String newName,
            @ForAll("descriptions") String newDescription
    ) {
        User owner = buildUser("owner");
        Room room = buildRoom(roomName, originalVisibility, owner);

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> roomService.updateRoom(room.getId(), owner, newName, newDescription, RoomVisibility.DIRECT));

        assertThat(ex.getMessage()).contains("Rooms cannot be converted to DIRECT visibility");
    }

    /**
     * Property 4c: Creating a room with DIRECT visibility → HTTP 400
     *
     * For any CreateRoomRequest with visibility = DIRECT, the room creation endpoint
     * SHALL reject with HTTP 400.
     *
     * Validates: Requirements 5.4, 15.1
     */
    @Property(tries = 20)
    void creatingRoomWithDirectVisibility_shallReturnBadRequest(
            @ForAll("roomNames") String roomName,
            @ForAll("descriptions") String description
    ) {
        CreateRoomRequest request = new CreateRoomRequest(
                roomName.isEmpty() ? "a" : roomName,
                description,
                RoomVisibility.DIRECT
        );

        ResponseEntity<?> response = roomApiController.createRoom(request, () -> "test@test.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
