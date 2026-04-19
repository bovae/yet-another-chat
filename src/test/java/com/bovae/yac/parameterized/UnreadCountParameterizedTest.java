package com.bovae.yac.parameterized;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parameterized boundary tests for unread count computation.
 *
 * <p>Validates:
 * <ul>
 *   <li>CP 24 — Unread count equals min(room.nextWatermark - 1 - lastReadWatermark, 999)</li>
 * </ul>
 *
 * <p>Requirements: 6.9
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class UnreadCountParameterizedTest {

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UnreadMarkerRepository unreadMarkerRepository;

    @Autowired
    private com.bovae.yac.repository.UserRepository userRepository;

    private User user;
    private Room room;

    @BeforeEach
    void setUp() {
        UserDto userDto = userService.register("unread@test.com", "unreaduser", "password123");
        user = userRepository.findById(userDto.id()).orElseThrow();
        room = roomService.getRoomById(roomService.createRoom("unread-room", "test room", RoomVisibility.PUBLIC, user).id());
    }

    /**
     * Verifies that computeUnreadCount returns the correct value for various watermark
     * combinations, including zero unread, small counts, large counts, and values
     * exceeding the display cap of 999.
     *
     * <p>Formula: min(room.nextWatermark - 1 - lastReadWatermark, DISPLAY_CAP)
     * where DISPLAY_CAP = 999. Result is clamped to 0 minimum.
     *
     * <p>Validates: CP 24
     */
    @ParameterizedTest(name = "[{index}] nextWatermark={0}, lastReadWatermark={1} → expected={2}")
    @CsvSource({
            "1,   0,   0",
            "2,   0,   1",
            "501, 0,   500",
            "1000, 0,  999",
            "2000, 0,  999",
            "10,  5,   4",
            "5,   4,   0"
    })
    void computeUnreadCount_withVariousWatermarks(long nextWatermark, long lastReadWatermark,
                                                  int expectedUnread) {
        // Set the room's nextWatermark to the desired value
        room.setNextWatermark(nextWatermark);
        roomRepository.save(room);

        // Create an UnreadMarker with the specified lastReadWatermark
        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(lastReadWatermark)
                .build();
        unreadMarkerRepository.save(marker);

        int actual = notificationService.computeUnreadCount(user, room);

        assertEquals(expectedUnread, actual,
                "Unread count for nextWatermark=%d, lastReadWatermark=%d should be %d"
                        .formatted(nextWatermark, lastReadWatermark, expectedUnread));
    }

    /**
     * Verifies that computeUnreadCount returns 0 when no UnreadMarker exists for the user/room.
     *
     * <p>Validates: CP 24
     */
    @ParameterizedTest(name = "[{index}] nextWatermark={0}, no marker → expected=0")
    @CsvSource({
            "1",
            "100",
            "1500"
    })
    void computeUnreadCount_noMarker_returnsZero(long nextWatermark) {
        room.setNextWatermark(nextWatermark);
        roomRepository.save(room);

        int actual = notificationService.computeUnreadCount(user, room);

        assertEquals(0, actual,
                "Unread count should be 0 when no marker exists");
    }
}
