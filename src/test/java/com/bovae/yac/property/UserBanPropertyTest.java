package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
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
 * Property-based tests for UserBanService access control enforcement.
 *
 * Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 14.3
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class UserBanPropertyTest {

    @Autowired
    private UserBanService userBanService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserBanRepository userBanRepository;

    @AfterTry
    void cleanup() {
        userBanRepository.deleteAll();
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

    // Feature: online-chat-server, Property 11: UserBan access control enforcement
    /**
     * Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 14.3
     *
     * For any UserBan where User A blocks User B:
     * 1. Establish friendship between A and B
     * 2. User A bans User B → friendship deleted, ban exists
     * 3. User B tries to send friend request to A → ForbiddenException
     * 4. User A unbans User B → ban gone
     * 5. User B can now send friend request to A → succeeds
     */
    @Property(tries = 10)
    void userBanAccessControlEnforcement(
            @ForAll("validEmails") String emailA,
            @ForAll("validUsernames") String usernameA,
            @ForAll("validPasswords") String passwordA,
            @ForAll("validEmails") String emailB,
            @ForAll("validUsernames") String usernameB,
            @ForAll("validPasswords") String passwordB
    ) {
        // Create two distinct users
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        User userA = userRepository.findById(userService.register(emailA + suffix, usernameA + suffix, passwordA).id()).orElseThrow();
        User userB = userRepository.findById(userService.register(emailB + suffix + "b", usernameB + suffix + "b", passwordB).id()).orElseThrow();

        // Step 1: Establish friendship between A and B
        Friendship friendship = friendshipService.sendFriendRequest(userA, userB, "hello");
        friendshipService.acceptFriendRequest(friendship.getId(), userB);

        // Verify friendship exists and is ACCEPTED
        Friendship accepted = friendshipRepository.findById(friendship.getId()).orElseThrow();
        assertThat(accepted.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);

        // Step 2: User A bans User B → friendship deleted, ban exists
        UserBan ban = userBanService.banUser(userA, userB);
        assertThat(ban).isNotNull();
        assertThat(ban.getId()).isNotNull();
        assertThat(ban.getBlocker().getId()).isEqualTo(userA.getId());
        assertThat(ban.getBlocked().getId()).isEqualTo(userB.getId());

        // Verify ban exists
        assertThat(userBanRepository.existsByBlockerAndBlocked(userA, userB)).isTrue();

        // Verify friendship was deleted
        assertThat(friendshipRepository.findByRequesterAndRecipient(userA, userB)).isEmpty();
        assertThat(friendshipRepository.findByRequesterAndRecipient(userB, userA)).isEmpty();

        // Step 3: User B tries to send friend request to A → ForbiddenException
        assertThatThrownBy(() -> friendshipService.sendFriendRequest(userB, userA, "request"))
                .isInstanceOf(ForbiddenException.class);

        // Also verify A cannot send friend request to B (ban is checked bidirectionally)
        assertThatThrownBy(() -> friendshipService.sendFriendRequest(userA, userB, "request"))
                .isInstanceOf(ForbiddenException.class);

        // Step 4: User A unbans User B → ban gone
        userBanService.unbanUser(userA, userB);
        assertThat(userBanRepository.existsByBlockerAndBlocked(userA, userB)).isFalse();

        // Step 5: User B can now send friend request to A → succeeds
        Friendship newRequest = friendshipService.sendFriendRequest(userB, userA, "let's be friends again");
        assertThat(newRequest).isNotNull();
        assertThat(newRequest.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(newRequest.getRequester().getId()).isEqualTo(userB.getId());
        assertThat(newRequest.getRecipient().getId()).isEqualTo(userA.getId());
    }
}
