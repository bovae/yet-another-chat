package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation property tests for room membership operations.
 *
 * Verifies that isMember(), existsByRoomAndUser(), joinPublicRoom(), and leaveRoom()
 * continue to work correctly after the DTO changes.
 *
 * Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.10
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class MemberListPreservationPropertyTest {

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
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(30)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> usernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    /**
     * **Validates: Requirements 3.2, 3.4, 3.5, 3.10**
     *
     * For any random room/user pair:
     * - isMember() returns true for actual members and false for non-members
     * - existsByRoomAndUser() produces identical results to isMember()
     * - joinPublicRoom() successfully adds a non-member to a public room
     * - After joining, isMember() returns true
     * - leaveRoom() successfully removes the member
     * - After leaving, isMember() returns false
     */
    @Property(tries = 10)
    void membershipOperationsPreserved(
            @ForAll("roomNames") String roomName,
            @ForAll("usernames") String ownerName,
            @ForAll("usernames") String joinerName
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Create owner and joiner in a transaction
        UserDto ownerDto = userService.register(
                ownerName + suffix + "@test.com",
                ownerName + suffix,
                "password123"
        );
        UserDto joinerDto = userService.register(
                joinerName + suffix + "j@test.com",
                joinerName + suffix + "j",
                "password123"
        );

        // Create room with owner inside a transaction
        Room room = transactionTemplate.execute(status -> {
            User owner = userRepository.findById(ownerDto.id()).orElseThrow();
            Room r = Room.builder()
                    .name(roomName + "-" + suffix)
                    .description("test")
                    .visibility(RoomVisibility.PUBLIC)
                    .owner(owner)
                    .nextWatermark(1L)
                    .build();
            r = roomRepository.save(r);

            RoomMember ownerMember = RoomMember.builder()
                    .room(r)
                    .user(owner)
                    .role(RoomRole.OWNER)
                    .build();
            roomMemberRepository.save(ownerMember);
            return r;
        });

        // Reload entities outside the setup transaction
        Room roomRef = transactionTemplate.execute(status ->
                roomRepository.findById(room.getId()).orElseThrow()
        );
        User ownerRef = transactionTemplate.execute(status ->
                userRepository.findById(ownerDto.id()).orElseThrow()
        );
        User joinerRef = transactionTemplate.execute(status ->
                userRepository.findById(joinerDto.id()).orElseThrow()
        );

        // isMember() — owner is a member, joiner is not
        boolean ownerIsMember = roomMemberService.isMember(roomRef, ownerRef);
        assertThat(ownerIsMember)
                .as("Owner should be a member of the room")
                .isTrue();

        boolean joinerIsMemberBefore = roomMemberService.isMember(roomRef, joinerRef);
        assertThat(joinerIsMemberBefore)
                .as("Joiner should NOT be a member before joining")
                .isFalse();

        // existsByRoomAndUser() — should match isMember()
        boolean ownerExists = roomMemberRepository.existsByRoomAndUser(roomRef, ownerRef);
        assertThat(ownerExists)
                .as("existsByRoomAndUser should match isMember for owner")
                .isEqualTo(ownerIsMember);

        boolean joinerExistsBefore = roomMemberRepository.existsByRoomAndUser(roomRef, joinerRef);
        assertThat(joinerExistsBefore)
                .as("existsByRoomAndUser should match isMember for joiner before join")
                .isEqualTo(joinerIsMemberBefore);

        // joinPublicRoom() — joiner joins the room
        roomMemberService.joinPublicRoom(roomRef, joinerRef);

        boolean joinerIsMemberAfterJoin = roomMemberService.isMember(roomRef, joinerRef);
        assertThat(joinerIsMemberAfterJoin)
                .as("Joiner should be a member after joining")
                .isTrue();

        // leaveRoom() — joiner leaves the room
        roomMemberService.leaveRoom(roomRef, joinerRef);

        boolean joinerIsMemberAfterLeave = roomMemberService.isMember(roomRef, joinerRef);
        assertThat(joinerIsMemberAfterLeave)
                .as("Joiner should NOT be a member after leaving")
                .isFalse();
    }
}
