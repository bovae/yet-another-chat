package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
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
 * Property-based tests for friend request text passthrough.
 *
 * Validates: Requirements 3.1, 3.3
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class FriendRequestTextPropertyTest {

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
    Arbitrary<String> requestTexts() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.just(""),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(100),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(50)
                        .filter(s -> s.chars().anyMatch(c -> c > 127)),
                Arbitraries.strings().alpha().ofLength(255)
        );
    }

    // Feature: ui-completion-and-fixes, Property 2: Friend request text passthrough
    /**
     * Validates: Requirements 3.1, 3.3
     *
     * For any SendFriendRequest containing a requestText string (including null),
     * the persisted Friendship entity's requestText field SHALL equal the value from the request body.
     */
    @Property(tries = 100)
    void friendRequestTextIsPersistedExactly(
            @ForAll("requestTexts") String requestText
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User requester = userRepository.findById(
                userService.register("req" + suffix + "@example.com", "req" + suffix, "password123").id()
        ).orElseThrow();
        User recipient = userRepository.findById(
                userService.register("rec" + suffix + "@example.com", "rec" + suffix, "password123").id()
        ).orElseThrow();

        Friendship friendship = friendshipService.sendFriendRequest(requester, recipient, requestText);

        // Verify the returned entity has the correct requestText
        assertThat(friendship.getRequestText()).isEqualTo(requestText);

        // Verify the persisted entity also matches
        Friendship persisted = friendshipRepository.findById(friendship.getId()).orElseThrow();
        assertThat(persisted.getRequestText()).isEqualTo(requestText);
    }
}
