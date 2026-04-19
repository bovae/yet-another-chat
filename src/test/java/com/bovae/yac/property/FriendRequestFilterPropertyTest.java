package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.FriendshipDto;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for friend request endpoint filtering.
 *
 * Validates: Requirements 13.7
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class FriendRequestFilterPropertyTest {

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
    Arbitrary<FriendshipStatus> friendshipStatuses() {
        return Arbitraries.of(FriendshipStatus.PENDING, FriendshipStatus.ACCEPTED, FriendshipStatus.DECLINED);
    }

    // Feature: ui-completion-and-fixes, Property 11: Friend request endpoint filtering
    /**
     * Validates: Requirements 13.7
     *
     * incoming returns only PENDING where user is recipient;
     * outgoing returns only PENDING where user is requester.
     */
    @Property(tries = 4)
    void friendRequestEndpointFiltering(
            @ForAll("friendshipStatuses") FriendshipStatus statusAB,
            @ForAll("friendshipStatuses") FriendshipStatus statusCB,
            @ForAll("friendshipStatuses") FriendshipStatus statusBA
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Create 3 users: A, B, C
        User userA = userRepository.findById(
                userService.register("a" + suffix + "@example.com", "usera" + suffix, "password123").id()
        ).orElseThrow();
        User userB = userRepository.findById(
                userService.register("b" + suffix + "@example.com", "userb" + suffix, "password123").id()
        ).orElseThrow();
        User userC = userRepository.findById(
                userService.register("c" + suffix + "@example.com", "userc" + suffix, "password123").id()
        ).orElseThrow();

        // Create friendships with varying statuses:
        // A -> B with statusAB (userB is recipient)
        Friendship fAB = friendshipRepository.save(Friendship.builder()
                .requester(userA)
                .recipient(userB)
                .status(statusAB)
                .build());

        // C -> B with statusCB (userB is recipient)
        Friendship fCB = friendshipRepository.save(Friendship.builder()
                .requester(userC)
                .recipient(userB)
                .status(statusCB)
                .build());

        // B -> A with statusBA (userB is requester, but need to avoid duplicate A-B pair)
        // Use B -> C instead to avoid unique constraint violation
        Friendship fBC = friendshipRepository.save(Friendship.builder()
                .requester(userB)
                .recipient(userC)
                .status(statusBA)
                .build());

        // Test incoming for userB: should return only PENDING where userB is recipient
        List<FriendshipDto> incoming = friendshipService.listPendingIncoming(userB);
        for (FriendshipDto dto : incoming) {
            assertThat(dto.status()).isEqualTo(FriendshipStatus.PENDING);
            assertThat(dto.recipientId()).isEqualTo(userB.getId());
        }

        // Verify correct count: fAB if PENDING + fCB if PENDING
        long expectedIncoming = 0;
        if (statusAB == FriendshipStatus.PENDING) {
            expectedIncoming++;
        }
        if (statusCB == FriendshipStatus.PENDING) {
            expectedIncoming++;
        }
        assertThat(incoming).hasSize((int) expectedIncoming);

        // Test outgoing for userB: should return only PENDING where userB is requester
        List<FriendshipDto> outgoing = friendshipService.listPendingOutgoing(userB);
        for (FriendshipDto dto : outgoing) {
            assertThat(dto.status()).isEqualTo(FriendshipStatus.PENDING);
            assertThat(dto.requesterId()).isEqualTo(userB.getId());
        }

        // Verify correct count: fBC if PENDING
        long expectedOutgoing = (statusBA == FriendshipStatus.PENDING) ? 1 : 0;
        assertThat(outgoing).hasSize((int) expectedOutgoing);
    }
}
