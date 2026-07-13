package com.bovae.yac.parameterized;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.service.MessageService;
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
 * Parameterized tests for unread count computation. Unread now equals the number of undeleted
 * message rows with watermark greater than the reader's last-read watermark (R1-57).
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
    private MessageService messageService;

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
        room = roomService.getRoomById(
                roomService.createRoom("unread-room", "test room", RoomVisibility.PUBLIC, user).id());
    }

    @ParameterizedTest(name = "[{index}] {0} messages, read up to watermark {1} → unread {2}")
    @CsvSource({
            "0, 0, 0",
            "3, 0, 3",
            "3, 1, 2",
            "5, 5, 0",
            "4, 2, 2"
    })
    void computeUnreadCount_reflectsUndeletedRows(int messageCount, long lastRead, int expectedUnread) {
        for (int i = 0; i < messageCount; i++) {
            Room fresh = roomService.getRoomById(room.getId());
            messageService.sendMessage(fresh, user, "message " + i, null);
        }

        UnreadMarker marker = UnreadMarker.builder()
                .user(user)
                .room(room)
                .lastReadWatermark(lastRead)
                .build();
        unreadMarkerRepository.save(marker);

        Room fresh = roomService.getRoomById(room.getId());
        int actual = notificationService.computeUnreadCount(user, fresh);

        assertEquals(expectedUnread, actual,
                "Unread for %d messages read-up-to %d should be %d".formatted(messageCount, lastRead, expectedUnread));
    }
}
