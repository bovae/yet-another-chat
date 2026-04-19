package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
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

/**
 * Property-based tests for FriendshipService lifecycle state transitions.
 *
 * Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class FriendshipPropertyTest {

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @AfterTry
    void cleanup() {
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

    @Provide
    Arbitrary<String> requestTexts() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(0)
                .ofMaxLength(100);
    }

    // Feature: online-chat-server, Property 10: Friendship lifecycle state transitions
    /**
     * Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5
     *
     * Tests the full accept lifecycle: send request (verify PENDING + request text),
     * accept (verify ACCEPTED), remove (verify deleted).
     */
    @Property(tries = 50)
    void friendshipAcceptLifecycle(
            @ForAll("validEmails") String requesterEmail,
            @ForAll("validUsernames") String requesterUsername,
            @ForAll("validPasswords") String requesterPassword,
            @ForAll("validEmails") String recipientEmail,
            @ForAll("validUsernames") String recipientUsername,
            @ForAll("validPasswords") String recipientPassword,
            @ForAll("requestTexts") String requestText
    ) {
        // Register two distinct users
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        User requester = userRepository.findById(userService.register(requesterEmail + suffix, requesterUsername + suffix, requesterPassword).id()).orElseThrow();
        User recipient = userRepository.findById(userService.register(recipientEmail + suffix + "r", recipientUsername + suffix + "r", recipientPassword).id()).orElseThrow();

        // Send friend request — SHALL create with PENDING status and optional request text
        Friendship friendship = friendshipService.sendFriendRequest(requester, recipient, requestText);
        assertThat(friendship).isNotNull();
        assertThat(friendship.getId()).isNotNull();
        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(friendship.getRequestText()).isEqualTo(requestText);
        assertThat(friendship.getRequester().getId()).isEqualTo(requester.getId());
        assertThat(friendship.getRecipient().getId()).isEqualTo(recipient.getId());

        // Verify persisted
        Friendship persisted = friendshipRepository.findById(friendship.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(persisted.getRequestText()).isEqualTo(requestText);

        // Accept — SHALL transition to ACCEPTED
        Friendship accepted = friendshipService.acceptFriendRequest(friendship.getId(), recipient);
        assertThat(accepted.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);

        // Verify persisted status
        Friendship persistedAccepted = friendshipRepository.findById(friendship.getId()).orElseThrow();
        assertThat(persistedAccepted.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);

        // Remove — SHALL delete the record
        friendshipService.removeFriend(friendship.getId(), requester);
        assertThat(friendshipRepository.findById(friendship.getId())).isEmpty();
    }

    // Feature: online-chat-server, Property 10: Friendship lifecycle state transitions
    /**
     * Validates: Requirements 6.1, 6.2, 6.4
     *
     * Tests the decline path: send request (verify PENDING), decline (verify DECLINED).
     */
    @Property(tries = 50)
    void friendshipDeclineLifecycle(
            @ForAll("validEmails") String requesterEmail,
            @ForAll("validUsernames") String requesterUsername,
            @ForAll("validPasswords") String requesterPassword,
            @ForAll("validEmails") String recipientEmail,
            @ForAll("validUsernames") String recipientUsername,
            @ForAll("validPasswords") String recipientPassword,
            @ForAll("requestTexts") String requestText
    ) {
        // Register two distinct users
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        User requester = userRepository.findById(userService.register(requesterEmail + suffix, requesterUsername + suffix, requesterPassword).id()).orElseThrow();
        User recipient = userRepository.findById(userService.register(recipientEmail + suffix + "r", recipientUsername + suffix + "r", recipientPassword).id()).orElseThrow();

        // Send friend request — SHALL create with PENDING status
        Friendship friendship = friendshipService.sendFriendRequest(requester, recipient, requestText);
        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(friendship.getRequestText()).isEqualTo(requestText);

        // Decline — SHALL transition to DECLINED
        Friendship declined = friendshipService.declineFriendRequest(friendship.getId(), recipient);
        assertThat(declined.getStatus()).isEqualTo(FriendshipStatus.DECLINED);

        // Verify persisted status
        Friendship persistedDeclined = friendshipRepository.findById(friendship.getId()).orElseThrow();
        assertThat(persistedDeclined.getStatus()).isEqualTo(FriendshipStatus.DECLINED);
    }
}
