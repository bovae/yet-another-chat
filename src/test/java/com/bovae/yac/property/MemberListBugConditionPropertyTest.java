package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomMemberService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug Condition property test for member list lazy initialization fix.
 *
 * Generates random room configurations (1–10 members with varied roles and nullable
 * displayNames), persists them inside a transaction, then calls listMembers() OUTSIDE
 * any transaction to verify session-absent mapping works correctly.
 *
 * Validates: Requirements 2.1, 2.2, 2.3
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class MemberListBugConditionPropertyTest {

    @Autowired
    private RoomMemberService roomMemberService;

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
    Arbitrary<Integer> memberCounts() {
        return Arbitraries.integers().between(1, 10);
    }

    @Provide
    Arbitrary<RoomRole> nonOwnerRoles() {
        return Arbitraries.of(RoomRole.MEMBER, RoomRole.ADMIN);
    }

    @Provide
    Arbitrary<String> nullableDisplayNames() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30)
        );
    }

    /**
     * **Validates: Requirements 2.1, 2.2, 2.3**
     *
     * For any room with 1–10 members (first member is OWNER, rest are MEMBER or ADMIN,
     * each with a nullable displayName), calling listMembers() outside a transaction
     * SHALL return RoomMemberDto records where:
     * - userId is non-null
     * - username is non-null
     * - role is non-null
     * - joinedAt is non-null
     * - DTO count matches persisted member count
     *
     * On UNFIXED code this would throw LazyInitializationException because the User
     * proxy is uninitialized after the session closes.
     */
    @Property(tries = 10)
    void listMembersOutsideTransactionReturnsFullyPopulatedDtos(
            @ForAll("memberCounts") int memberCount,
            @ForAll("nullableDisplayNames") String ownerDisplayName
    ) {
        // Persist entities inside a transaction
        List<UUID> userIds = new ArrayList<>();
        UUID roomId = transactionTemplate.execute(status -> {
            // Create owner
            String ownerSuffix = UUID.randomUUID().toString().substring(0, 8);
            UserDto ownerDto = userService.register(
                    "owner-" + ownerSuffix + "@test.com",
                    "owner" + ownerSuffix,
                    "password123"
            );
            User owner = userRepository.findById(ownerDto.id()).orElseThrow();
            if (ownerDisplayName != null) {
                owner.setDisplayName(ownerDisplayName);
                owner = userRepository.save(owner);
            }
            userIds.add(owner.getId());

            // Create room
            Room room = Room.builder()
                    .name("room-" + UUID.randomUUID().toString().substring(0, 8))
                    .description("test room")
                    .visibility(RoomVisibility.PUBLIC)
                    .owner(owner)
                    .nextWatermark(1L)
                    .build();
            room = roomRepository.save(room);

            // Add owner as OWNER member
            RoomMember ownerMember = RoomMember.builder()
                    .room(room)
                    .user(owner)
                    .role(RoomRole.OWNER)
                    .build();
            roomMemberRepository.save(ownerMember);

            // Add additional members
            for (int i = 1; i < memberCount; i++) {
                String suffix = UUID.randomUUID().toString().substring(0, 8);
                UserDto memberDto = userService.register(
                        "member-" + suffix + "@test.com",
                        "member" + suffix,
                        "password123"
                );
                User memberUser = userRepository.findById(memberDto.id()).orElseThrow();
                // Randomly set or skip displayName (alternate null/non-null)
                if (i % 2 == 0) {
                    memberUser.setDisplayName("Display-" + suffix);
                    userRepository.save(memberUser);
                }
                userIds.add(memberUser.getId());

                RoomRole role = (i % 3 == 0) ? RoomRole.ADMIN : RoomRole.MEMBER;
                RoomMember member = RoomMember.builder()
                        .room(room)
                        .user(memberUser)
                        .role(role)
                        .build();
                roomMemberRepository.save(member);
            }

            return room.getId();
        });

        // Call listMembers() OUTSIDE any transaction — session-absent mapping
        Room roomRef = transactionTemplate.execute(status ->
                roomRepository.findById(roomId).orElseThrow()
        );

        List<RoomMemberDto> dtos = roomMemberService.listMembers(roomRef);

        // Assert DTO count matches member count
        assertThat(dtos)
                .as("DTO count should match persisted member count")
                .hasSize(memberCount);

        // Assert all required fields are non-null
        for (RoomMemberDto dto : dtos) {
            assertThat(dto.userId())
                    .as("userId must be non-null")
                    .isNotNull();
            assertThat(dto.username())
                    .as("username must be non-null")
                    .isNotNull();
            assertThat(dto.role())
                    .as("role must be non-null")
                    .isNotNull();
            assertThat(dto.joinedAt())
                    .as("joinedAt must be non-null")
                    .isNotNull();
        }

        // Assert all persisted user IDs are present in DTOs
        List<UUID> dtoUserIds = dtos.stream().map(RoomMemberDto::userId).toList();
        assertThat(dtoUserIds)
                .as("All persisted user IDs should appear in DTOs")
                .containsExactlyInAnyOrderElementsOf(userIds);
    }
}
