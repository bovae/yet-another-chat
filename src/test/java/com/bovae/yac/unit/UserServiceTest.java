package com.bovae.yac.unit;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.dto.UserMapper;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.PasswordResetTokenRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService}.
 *
 * <p>Validates Correctness Properties: CP 1, CP 2, CP 3, CP 8.
 * <p>Requirements: 2.1, 2.2, 2.3, 2.4, 2.5.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private UnreadMarkerRepository unreadMarkerRepository;

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private UserBanRepository userBanRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Mock
    private RoomService roomService;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserService userService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("$2a$10$hashedpassword")
                .build();
    }

    /**
     * Validates CP 2: Registration with a unique email and username creates a User
     * with a BCrypt-hashed password.
     */
    @Test
    void register_withUniqueEmailAndUsername_createsUserWithBCryptHash() {
        String email = "bob@test.com";
        String username = "bob";
        String rawPassword = "securePass123";
        String encodedPassword = "$2a$10$encodedHash";

        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(userRepository.existsByUsername(username)).thenReturn(false);
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(userMapper.toDto(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            return new UserDto(u.getId(), u.getEmail(), u.getUsername(), u.getDisplayName(), u.getCreatedAt());
        });

        UserDto result = userService.register(email, username, rawPassword);

        assertThat(result.email()).isEqualTo(email);
        assertThat(result.username()).isEqualTo(username);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo(encodedPassword);
        verify(passwordEncoder).encode(rawPassword);
    }

    /**
     * Validates CP 1: Registration with a duplicate email throws ConflictException.
     */
    @Test
    void register_withDuplicateEmail_throwsConflictException() {
        when(userRepository.existsByEmail("alice@test.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register("alice@test.com", "newuser", "pass123"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Email is already taken");

        verify(userRepository, never()).save(any());
    }

    /**
     * Validates CP 1: Registration with a duplicate username throws ConflictException.
     */
    @Test
    void register_withDuplicateUsername_throwsConflictException() {
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.register("new@test.com", "alice", "pass123"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Username is already taken");

        verify(userRepository, never()).save(any());
    }

    /**
     * Validates CP 3: updateProfile with a changed username throws ForbiddenException.
     */
    @Test
    void updateProfile_withChangedUsername_throwsForbiddenException() {
        when(userRepository.findById(existingUser.getId())).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> userService.updateProfile(existingUser.getId(), "New Display", "differentUsername"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Username cannot be changed");

        verify(userRepository, never()).save(any());
    }

    /**
     * Validates CP 8: deleteAccount removes owned rooms, memberships, friendships,
     * user bans, unread markers, password reset tokens, and sessions.
     */
    @Test
    @SuppressWarnings("unchecked")
    void deleteAccount_removesAllAssociatedData() {
        UUID userId = existingUser.getId();

        // Owned rooms
        Room ownedRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("alice-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(existingUser)
                .nextWatermark(1L)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
        when(roomRepository.findByOwner(existingUser)).thenReturn(List.of(ownedRoom));

        // Memberships in other rooms
        RoomMember membership = RoomMember.builder()
                .room(Room.builder().id(UUID.randomUUID()).name("other-room").build())
                .user(existingUser)
                .build();
        when(roomMemberRepository.findByUser(existingUser)).thenReturn(List.of(membership));

        // Friendships
        Friendship friendship = Friendship.builder().id(UUID.randomUUID()).build();
        when(friendshipRepository.findByRequesterOrRecipient(existingUser, existingUser))
                .thenReturn(List.of(friendship));

        // User bans (as blocker and blocked)
        UserBan banAsBlocker = UserBan.builder().id(UUID.randomUUID()).build();
        UserBan banAsBlocked = UserBan.builder().id(UUID.randomUUID()).build();
        when(userBanRepository.findByBlocker(existingUser)).thenReturn(List.of(banAsBlocker));
        when(userBanRepository.findByBlocked(existingUser)).thenReturn(List.of(banAsBlocked));

        // Unread markers
        UnreadMarker marker = UnreadMarker.builder().user(existingUser).build();
        when(unreadMarkerRepository.findByUser(existingUser)).thenReturn(List.of(marker));

        // Sessions
        Session mockSession = mock(Session.class);
        FindByIndexNameSessionRepository<Session> typedSessionRepo =
                (FindByIndexNameSessionRepository<Session>) sessionRepository;
        when(typedSessionRepo.findByPrincipalName(existingUser.getEmail()))
                .thenReturn(Map.of("session-1", mockSession));

        userService.deleteAccount(userId);

        // Verify owned rooms cascade-deleted
        verify(roomService).deleteRoomCascade(ownedRoom);

        // Verify memberships removed
        verify(roomMemberRepository).deleteAll(List.of(membership));

        // Verify friendships removed
        verify(friendshipRepository).deleteAll(List.of(friendship));

        // Verify user bans removed (both directions)
        verify(userBanRepository).deleteAll(List.of(banAsBlocker));
        verify(userBanRepository).deleteAll(List.of(banAsBlocked));

        // Verify unread markers removed
        verify(unreadMarkerRepository).deleteAll(List.of(marker));

        // Verify password reset tokens removed
        verify(passwordResetTokenRepository).deleteByUser(existingUser);

        // Verify sessions invalidated
        verify(typedSessionRepo).deleteById("session-1");

        // Verify user deleted
        verify(userRepository).delete(existingUser);
    }
}
