package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserBanService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for DirectChatService access control.
 *
 * Validates: Requirements 14.1, 14.2, 14.5
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class DirectChatPropertyTest {

    @Autowired
    private DirectChatService directChatService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserBanService userBanService;

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserBanRepository userBanRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomRepository roomRepository;

    @AfterTry
    void cleanup() {
        userBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        friendshipRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Provide
    Arbitrary<String> validEmails() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(12)
                .map(local -> local.toLowerCase() + "@example.com");
    }

    @Provide
    Arbitrary<String> validUsernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> validPasswords() {
        return Arbitraries.strings()
                .ascii()
                .ofMinLength(8)
                .ofMaxLength(30);
    }

    // Feature: online-chat-server, Property 20: Direct chat access control
    /**
     * Validates: Requirements 14.1, 14.2, 14.5
     *
     * For any two Users, a Direct_Chat SHALL be creatable if and only if they are friends
     * and no mutual UserBan exists. Non-friends SHALL be rejected (ForbiddenException).
     * Direct_Chats SHALL have no admin moderation capabilities.
     */
    @Property(tries = 4)
    void directChatAccessControl(
            @ForAll("validEmails") String emailA,
            @ForAll("validUsernames") String usernameA,
            @ForAll("validPasswords") String passwordA,
            @ForAll("validEmails") String emailB,
            @ForAll("validUsernames") String usernameB,
            @ForAll("validPasswords") String passwordB
    ) {
        // Create two distinct users
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto userADto = userService.register(emailA + suffix, usernameA + suffix, passwordA);
        User userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register(emailB + suffix + "b", usernameB + suffix + "b", passwordB);
        User userB = userRepository.findById(userBDto.id()).orElseThrow();

        // Step 1: Two non-friends try to create direct chat → ForbiddenException
        assertThatThrownBy(() -> directChatService.getOrCreateDirectChat(userA, userB))
                .isInstanceOf(ForbiddenException.class);

        // Step 2: Make them friends, create direct chat → succeeds, room is DIRECT
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, "hi");
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        Room directChat = roomService.getRoomById(directChatService.getOrCreateDirectChat(userA, userB).id());
        assertThat(directChat).isNotNull();
        assertThat(directChat.getId()).isNotNull();
        assertThat(directChat.getVisibility()).isEqualTo(RoomVisibility.DIRECT);

        // Verify both users are members
        assertThat(roomMemberRepository.existsByRoomAndUser(directChat, userA)).isTrue();
        assertThat(roomMemberRepository.existsByRoomAndUser(directChat, userB)).isTrue();

        // Step 3: Verify direct chat room has no admin moderation capabilities
        // Neither member has admin privileges, so kick/ban/delete should fail
        assertThatThrownBy(() -> moderationService.kickMember(directChat, userA, userB))
                .isInstanceOf(ForbiddenException.class);

        assertThatThrownBy(() -> moderationService.banUserFromRoom(directChat, userA, userB))
                .isInstanceOf(ForbiddenException.class);

        assertThatThrownBy(() -> moderationService.grantAdminRole(directChat, userA, userB))
                .isInstanceOf(ForbiddenException.class);

        // Step 4: Ban one user, try to create another direct chat → ForbiddenException
        // First remove the friendship (ban does this automatically)
        userBanService.banUser(userA, userB);

        assertThatThrownBy(() -> directChatService.getOrCreateDirectChat(userA, userB))
                .isInstanceOf(ForbiddenException.class);
    }
}
