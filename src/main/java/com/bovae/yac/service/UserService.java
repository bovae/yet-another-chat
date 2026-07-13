package com.bovae.yac.service;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.dto.UserMapper;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.PasswordResetTokenRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UnreadMarkerRepository unreadMarkerRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserBanRepository userBanRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final RoomService roomService;
    private final UserMapper userMapper;

    @Transactional
    public UserDto register(String email, String username, String password) {
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already taken");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Username is already taken");
        }

        User user = User.builder()
                .email(email)
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .build();

        User saved = userRepository.save(user);
        LOG.info("Registered new user: username={}, id={}", saved.getUsername(), saved.getId());
        return userMapper.toDto(saved);
    }

    @Transactional
    public UserDto updateProfile(UUID userId, String displayName, String username) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: %s".formatted(userId)));

        if (username != null && !username.equals(user.getUsername())) {
            throw new ForbiddenException("Username cannot be changed");
        }

        user.setDisplayName(displayName);
        return userMapper.toDto(userRepository.save(user));
    }

    @Transactional
    public void deleteAccount(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: %s".formatted(userId)));

        LOG.info("Deleting account for user: username={}, id={}", user.getUsername(), user.getId());

        // 1. Delete all rooms owned by user (cascade: messages, attachments, members, bans, invitations)
        List<Room> ownedRooms = roomRepository.findByOwner(user);
        for (Room room : ownedRooms) {
            roomService.deleteRoomCascade(room);
        }

        // 2. Remove user's memberships from other rooms
        roomMemberRepository.deleteAll(roomMemberRepository.findByUser(user));

        // 3. Delete all friendships involving user
        friendshipRepository.deleteAll(friendshipRepository.findByRequesterOrRecipient(user, user));

        // 4. Delete all user bans (both as blocker and blocked)
        userBanRepository.deleteAll(userBanRepository.findByBlocker(user));
        userBanRepository.deleteAll(userBanRepository.findByBlocked(user));

        // 5. Delete unread markers
        unreadMarkerRepository.deleteAll(unreadMarkerRepository.findByUser(user));

        // 6. Delete password reset tokens
        passwordResetTokenRepository.deleteByUser(user);

        // 7. Invalidate all sessions via Spring Session Redis
        invalidateAllSessions(user.getEmail());

        // 8. Delete user record
        userRepository.delete(user);

        LOG.info("Account deleted for user: username={}", user.getUsername());
    }

    @Transactional(readOnly = true)
    public Optional<UserDto> findByUsername(String username) {
        return userRepository.findByUsername(username).map(userMapper::toDto);
    }

    @Transactional(readOnly = true)
    public UserDto getById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: %s".formatted(userId)));
        return userMapper.toDto(user);
    }

    private void invalidateAllSessions(String principalName) {
        sessionRepository.findByPrincipalName(principalName)
                .forEach((sessionId, session) -> {
                    sessionRepository.deleteById(sessionId);
                    LOG.debug("Invalidated session: {}", sessionId);
                });
    }
}
