package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceVisibilityService;
import com.bovae.yac.ws.StompAuthChannelInterceptor;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Unit tests for {@link StompAuthChannelInterceptor}.
 *
 * <p>Validates inbound STOMP authorization (design D4): unauthenticated CONNECT rejection (R1-62),
 * SUBSCRIBE authorization against room membership (R1-09) and presence visibility (R1-65).
 */
@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    private static final String USER_EMAIL = "owner@test.com";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private RoomBanRepository roomBanRepository;

    @Mock
    private PresenceVisibilityService presenceVisibilityService;

    @InjectMocks
    private StompAuthChannelInterceptor interceptor;

    private Principal principal;
    private User user;

    @BeforeEach
    void setUp() {
        principal = () -> USER_EMAIL;
        user = User.builder()
                .id(UUID.randomUUID())
                .email(USER_EMAIL)
                .username("owner")
                .passwordHash("$2a$10$hashedpassword")
                .build();
    }

    // --- CONNECT ---

    @Test
    void preSend_shouldRejectConnect_whenUserIsNull() {
        Message<byte[]> message = stompMessage(StompCommand.CONNECT, null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Unauthenticated");
    }

    @Test
    void preSend_shouldPassThrough_whenConnectIsAuthenticated() {
        Message<byte[]> message = stompMessage(StompCommand.CONNECT, principal, null);

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        verifyNoInteractions(userRepository, roomRepository, roomMemberRepository, presenceVisibilityService);
    }

    // --- SUBSCRIBE routing ---

    @Test
    void preSend_shouldRejectSubscribe_whenUserIsNull() {
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, null, "/topic/room." + UUID.randomUUID());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Authentication required to subscribe");
    }

    @ParameterizedTest(name = "destination={0}")
    @NullSource
    @ValueSource(strings = {"/user/queue/messages", "/user/queue/notifications"})
    void preSend_shouldPassThroughSubscribe_whenDestinationIsPersonalOrNull(String destination) {
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, destination);

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        verifyNoInteractions(userRepository, roomRepository, roomMemberRepository, presenceVisibilityService);
    }

    /**
     * Validates R4-01: deny-by-default. Any destination that is not an exact room/presence topic
     * or a personal /user/ queue — including wildcard patterns AntPathMatcher would otherwise match
     * against every broadcast — is rejected.
     */
    @ParameterizedTest(name = "destination={0}")
    @ValueSource(
            strings = {
                "/topic/**",
                "/topic/room.**",
                "/queue/**",
                "/queue/private",
                "/topic/presence.**",
                "/topic/anything-else"
            })
    void preSend_shouldRejectSubscribe_whenDestinationIsWildcardOrUnrecognized(String destination) {
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, destination);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("not allowed");
    }

    // --- Room subscription authorization ---

    @Test
    void preSend_shouldAllowRoomSubscribe_whenRoomIsPublic() {
        UUID roomId = UUID.randomUUID();
        Room publicRoom = room(roomId, RoomVisibility.PUBLIC);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(publicRoom));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomBanRepository.existsByRoomAndUser(publicRoom, user)).thenReturn(false);

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/room." + roomId);

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        // Membership is not required for a public room, but the ban gate still runs.
        verify(roomMemberRepository, never()).existsByRoomAndUser(any(), any());
        verifyNoInteractions(presenceVisibilityService);
    }

    /** Validates R4-06: a banned user is rejected from a PUBLIC room's live stream. */
    @Test
    void preSend_shouldRejectRoomSubscribe_whenUserIsBannedFromPublicRoom() {
        UUID roomId = UUID.randomUUID();
        Room publicRoom = room(roomId, RoomVisibility.PUBLIC);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(publicRoom));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomBanRepository.existsByRoomAndUser(publicRoom, user)).thenReturn(true);

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/room." + roomId);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Banned from this room");
        verify(roomMemberRepository, never()).existsByRoomAndUser(any(), any());
    }

    @Test
    void preSend_shouldAllowRoomSubscribe_whenUserIsMemberOfPrivateRoom() {
        UUID roomId = UUID.randomUUID();
        Room privateRoom = room(roomId, RoomVisibility.PRIVATE);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(privateRoom));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomMemberRepository.existsByRoomAndUser(privateRoom, user)).thenReturn(true);

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/room." + roomId);

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
    }

    @Test
    void preSend_shouldRejectRoomSubscribe_whenUserIsNotMemberOfPrivateRoom() {
        UUID roomId = UUID.randomUUID();
        Room privateRoom = room(roomId, RoomVisibility.PRIVATE);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(privateRoom));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(roomMemberRepository.existsByRoomAndUser(privateRoom, user)).thenReturn(false);

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/room." + roomId);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Not a member of this room");
    }

    @Test
    void preSend_shouldRejectRoomSubscribe_whenRoomNotFound() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/room." + roomId);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Room not found");
    }

    // --- Presence subscription authorization ---

    @Test
    void preSend_shouldAllowPresenceSubscribe_whenTargetIsVisible() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(presenceVisibilityService.canView(user.getId(), targetId)).thenReturn(true);

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/presence." + targetId);

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
    }

    @Test
    void preSend_shouldRejectPresenceSubscribe_whenTargetIsNotVisible() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        when(presenceVisibilityService.canView(user.getId(), targetId)).thenReturn(false);

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/presence." + targetId);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Presence not visible");
    }

    // --- User resolution ---

    @Test
    void preSend_shouldRejectSubscribe_whenPrincipalMapsToUnknownUser() {
        UUID roomId = UUID.randomUUID();
        Room privateRoom = room(roomId, RoomVisibility.PRIVATE);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(privateRoom));
        when(userRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.empty());

        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, principal, "/topic/room." + roomId);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Unknown user");
    }

    // --- helpers ---

    private Room room(UUID id, RoomVisibility visibility) {
        return Room.builder()
                .id(id)
                .name("room-" + id)
                .visibility(visibility)
                .owner(user)
                .nextWatermark(1L)
                .build();
    }

    private static Message<byte[]> stompMessage(StompCommand command, Principal principalHeader, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (principalHeader != null) {
            accessor.setUser(principalHeader);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
