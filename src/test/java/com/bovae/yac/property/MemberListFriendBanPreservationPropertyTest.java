package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;

import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation property tests for friendship and user ban boolean checks.
 *
 * Verifies that areFriends() and isBanExistsBetween() produce identical boolean
 * results after the DTO changes.
 *
 * Validates: Requirements 3.6, 3.10
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class MemberListFriendBanPreservationPropertyTest {

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private UserBanService userBanService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomBanRepository roomBanRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserBanRepository userBanRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterTry
    void cleanup() {
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        friendshipRepository.deleteAll();
        userBanRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Provide
    Arbitrary<Boolean> shouldBeFriends() {
        return Arbitraries.of(true, false);
    }

    @Provide
    Arbitrary<Boolean> shouldBeBanned() {
        return Arbitraries.of(true, false);
    }

    /**
     * **Validates: Requirements 3.6, 3.10**
     *
     * For any random user pair:
     * - When no friendship exists, areFriends() returns false
     * - When an ACCEPTED friendship exists, areFriends() returns true
     * - When no ban exists, isBanExistsBetween() returns false
     * - When a ban exists (A blocks B), isBanExistsBetween() returns true in both directions
     * - areFriends() and isBanExistsBetween() produce correct boolean results
     *   regardless of the DTO changes
     */
    @Property(tries = 10)
    void friendshipAndBanBooleanChecksPreserved(
            @ForAll("shouldBeFriends") boolean makeFriends,
            @ForAll("shouldBeBanned") boolean makeBan
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Create two users
        UserDto userADto = userService.register(
                "usera-" + suffix + "@test.com",
                "usera" + suffix,
                "password123"
        );
        UserDto userBDto = userService.register(
                "userb-" + suffix + "@test.com",
                "userb" + suffix,
                "password123"
        );

        User userA = transactionTemplate.execute(status ->
                userRepository.findById(userADto.id()).orElseThrow()
        );
        User userB = transactionTemplate.execute(status ->
                userRepository.findById(userBDto.id()).orElseThrow()
        );

        // Initially: no friendship, no ban
        assertThat(friendshipService.areFriends(userA, userB))
                .as("Users should not be friends initially")
                .isFalse();
        assertThat(userBanService.isBanExistsBetween(userA, userB))
                .as("No ban should exist initially")
                .isFalse();

        if (makeFriends && !makeBan) {
            // Create an ACCEPTED friendship
            Friendship friendship = friendshipService.sendFriendRequest(userA, userB, "hi");
            friendshipService.acceptFriendRequest(friendship.getId(), userB);

            assertThat(friendshipService.areFriends(userA, userB))
                    .as("areFriends(A, B) should be true after accepting")
                    .isTrue();
            assertThat(friendshipService.areFriends(userB, userA))
                    .as("areFriends(B, A) should be true (bidirectional)")
                    .isTrue();

            // Ban should still be false
            assertThat(userBanService.isBanExistsBetween(userA, userB))
                    .as("No ban should exist when only friends")
                    .isFalse();
        }

        if (makeBan) {
            // Ban userB by userA (this also removes any friendship)
            userBanService.banUser(userA, userB);

            assertThat(userBanService.isBanExistsBetween(userA, userB))
                    .as("isBanExistsBetween(A, B) should be true after ban")
                    .isTrue();
            assertThat(userBanService.isBanExistsBetween(userB, userA))
                    .as("isBanExistsBetween(B, A) should be true (bidirectional check)")
                    .isTrue();

            // Friendship should be false (ban removes friendship)
            assertThat(friendshipService.areFriends(userA, userB))
                    .as("areFriends should be false when ban exists")
                    .isFalse();
        }

        if (!makeFriends && !makeBan) {
            // No relationship — both should be false
            assertThat(friendshipService.areFriends(userA, userB))
                    .as("areFriends should be false with no relationship")
                    .isFalse();
            assertThat(userBanService.isBanExistsBetween(userA, userB))
                    .as("isBanExistsBetween should be false with no relationship")
                    .isFalse();
        }
    }
}
