package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.RoomEvent;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.ws.TypingHandler;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for {@link TypingHandler}.
 *
 * <p>Covers roomId extraction (camelCase and snake_case), the missing/invalid-roomId guards, and the
 * member-only broadcast rule (R1-40) on the STOMP {@code /typing} mapping.
 */
@ExtendWith(MockitoExtension.class)
class TypingHandlerTest {

    private static final String USER_EMAIL = "member@test.com";

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @InjectMocks
    private TypingHandler typingHandler;

    private Principal principal;
    private User user;
    private Room room;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        principal = () -> USER_EMAIL;
        user = User.builder()
                .id(UUID.randomUUID())
                .email(USER_EMAIL)
                .username("member")
                .passwordHash("$2a$10$hash")
                .build();
        roomId = UUID.randomUUID();
        room = Room.builder()
                .id(roomId)
                .name("room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(user)
                .nextWatermark(1L)
                .build();
    }

    @Test
    void typing_broadcastsEvent_whenSenderIsMember() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.existsByRoomAndUser(room, user)).thenReturn(true);

        typingHandler.typing(Map.of("roomId", roomId.toString()), principal);

        ArgumentCaptor<RoomEvent> eventCaptor = ArgumentCaptor.forClass(RoomEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/room." + roomId + ".events"), eventCaptor.capture());
        RoomEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("TYPING");
        assertThat(event.roomId()).isEqualTo(roomId);
        assertThat(event.userId()).isEqualTo(user.getId());
        assertThat(event.username()).isEqualTo("member");
    }

    @Test
    void typing_broadcastsEvent_whenRoomIdSuppliedAsSnakeCase() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.existsByRoomAndUser(room, user)).thenReturn(true);

        typingHandler.typing(Map.of("room_id", roomId.toString()), principal);

        verify(messagingTemplate).convertAndSend(eq("/topic/room." + roomId + ".events"), any(RoomEvent.class));
    }

    @Test
    void typing_returnsSilently_whenRoomIdMissing() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        typingHandler.typing(Map.of(), principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void typing_returnsSilently_whenRoomIdNotAValidUuid() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        typingHandler.typing(Map.of("roomId", "not-a-uuid"), principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void typing_returnsSilently_whenRoomNotFound() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        typingHandler.typing(Map.of("roomId", roomId.toString()), principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void typing_returnsSilently_whenSenderIsNotMember() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.existsByRoomAndUser(room, user)).thenReturn(false);

        typingHandler.typing(Map.of("roomId", roomId.toString()), principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void typing_throwsResourceNotFound_whenUserUnknown() {
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> typingHandler.typing(Map.of("roomId", roomId.toString()), principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");
    }
}
