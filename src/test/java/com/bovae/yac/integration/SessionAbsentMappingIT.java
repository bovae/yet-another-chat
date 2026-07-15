package com.bovae.yac.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.DirectChatDto;
import com.bovae.yac.model.dto.FriendshipDto;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.dto.UserBanDto;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.UserBanService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests verifying that DTO mapping works correctly AFTER the Hibernate
 * session has closed (session-absent mapping). These tests intentionally do NOT use
 * {@code @Transactional} — the whole point is to confirm that JOIN FETCH queries
 * properly initialize lazy associations so MapStruct can map detached entities to DTOs.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SessionAbsentMappingIT {

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private FriendshipService friendshipService;

    @Autowired
    private DirectChatService directChatService;

    @Autowired
    private UserBanService userBanService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserBanRepository userBanRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            roomMemberRepository.deleteAll();
            friendshipRepository.deleteAll();
            userBanRepository.deleteAll();
            roomRepository.deleteAll();
            userRepository.deleteAll();
        });
    }
    // ---- 8.1 RoomMemberService.listMembers() ----

    @Test
    void listMembers_outsideTransaction_returnsPopulatedDtos() {
        Room room = transactionTemplate.execute(status -> {
            User owner = userRepository.save(User.builder()
                    .email("owner@test.com")
                    .username("owner")
                    .displayName("Owner User")
                    .passwordHash("hashed")
                    .build());

            User member = userRepository.save(User.builder()
                    .email("member@test.com")
                    .username("member")
                    .displayName("Member User")
                    .passwordHash("hashed")
                    .build());

            Room r = roomRepository.save(Room.builder()
                    .name("test-room")
                    .visibility(RoomVisibility.PUBLIC)
                    .owner(owner)
                    .build());

            roomMemberRepository.save(RoomMember.builder()
                    .room(r)
                    .user(owner)
                    .role(RoomRole.OWNER)
                    .build());

            roomMemberRepository.save(RoomMember.builder()
                    .room(r)
                    .user(member)
                    .role(RoomRole.MEMBER)
                    .build());

            return r;
        });

        // Called OUTSIDE any transaction — session is closed
        List<RoomMemberDto> dtos = roomMemberService.listMembers(room);

        assertThat(dtos).hasSize(2);
        for (RoomMemberDto dto : dtos) {
            assertThat(dto.userId()).isNotNull();
            assertThat(dto.username()).isNotNull();
            assertThat(dto.role()).isNotNull();
            assertThat(dto.joinedAt()).isNotNull();
            // displayName may be null for users without one, but our test data has it set
            assertThat(dto.displayName()).isNotNull();
        }

        assertThat(dtos).extracting(RoomMemberDto::username).containsExactlyInAnyOrder("owner", "member");
        assertThat(dtos).extracting(RoomMemberDto::displayName).containsExactlyInAnyOrder("Owner User", "Member User");
    }

    // ---- 8.2 FriendshipService.listFriends() ----

    @Test
    void listFriends_outsideTransaction_returnsPopulatedDtos() {
        User[] users = transactionTemplate.execute(status -> {
            User alice = userRepository.save(User.builder()
                    .email("alice@test.com")
                    .username("alice")
                    .displayName("Alice")
                    .passwordHash("hashed")
                    .build());

            User bob = userRepository.save(User.builder()
                    .email("bob@test.com")
                    .username("bob")
                    .displayName("Bob")
                    .passwordHash("hashed")
                    .build());

            friendshipRepository.save(Friendship.builder()
                    .requester(alice)
                    .recipient(bob)
                    .status(FriendshipStatus.ACCEPTED)
                    .requestText("Let's be friends")
                    .build());

            return new User[] {alice, bob};
        });

        User alice = users[0];

        // Called OUTSIDE any transaction — session is closed
        List<FriendshipDto> dtos = friendshipService.listFriends(alice);

        assertThat(dtos).hasSize(1);
        FriendshipDto dto = dtos.get(0);
        assertThat(dto.id()).isNotNull();
        assertThat(dto.requesterId()).isNotNull();
        assertThat(dto.requesterUsername()).isEqualTo("alice");
        assertThat(dto.requesterDisplayName()).isEqualTo("Alice");
        assertThat(dto.recipientId()).isNotNull();
        assertThat(dto.recipientUsername()).isEqualTo("bob");
        assertThat(dto.recipientDisplayName()).isEqualTo("Bob");
        assertThat(dto.status()).isEqualTo(FriendshipStatus.ACCEPTED);
        assertThat(dto.requestText()).isEqualTo("Let's be friends");
        assertThat(dto.createdAt()).isNotNull();
    }
    // ---- 8.3 DirectChatService.listDirectChats() ----

    @Test
    void listDirectChats_outsideTransaction_returnsPopulatedDtos() {
        User[] users = transactionTemplate.execute(status -> {
            User alice = userRepository.save(User.builder()
                    .email("alice-dc@test.com")
                    .username("alice_dc")
                    .displayName("Alice DC")
                    .passwordHash("hashed")
                    .build());

            User bob = userRepository.save(User.builder()
                    .email("bob-dc@test.com")
                    .username("bob_dc")
                    .displayName("Bob DC")
                    .passwordHash("hashed")
                    .build());

            Room directRoom = roomRepository.save(Room.builder()
                    .name("dm-direct-test")
                    .visibility(RoomVisibility.DIRECT)
                    .owner(alice)
                    .build());

            roomMemberRepository.save(RoomMember.builder()
                    .room(directRoom)
                    .user(alice)
                    .role(RoomRole.MEMBER)
                    .build());

            roomMemberRepository.save(RoomMember.builder()
                    .room(directRoom)
                    .user(bob)
                    .role(RoomRole.MEMBER)
                    .build());

            return new User[] {alice, bob};
        });

        User alice = users[0];

        // Called OUTSIDE any transaction — session is closed
        List<DirectChatDto> dtos = directChatService.listDirectChats(alice);

        assertThat(dtos).hasSize(1);
        DirectChatDto dto = dtos.get(0);
        assertThat(dto.id()).isNotNull();
        assertThat(dto.name()).isEqualTo("dm-direct-test");
        assertThat(dto.otherUserId()).isEqualTo(users[1].getId());
        assertThat(dto.otherUsername()).isEqualTo("bob_dc");
        assertThat(dto.otherDisplayName()).isEqualTo("Bob DC");
        assertThat(dto.createdAt()).isNotNull();
    }

    // ---- 8.4 UserBanService.listBannedUsers() ----

    @Test
    void listBannedUsers_outsideTransaction_returnsPopulatedDtos() {
        User[] users = transactionTemplate.execute(status -> {
            User blocker = userRepository.save(User.builder()
                    .email("blocker@test.com")
                    .username("blocker")
                    .displayName("Blocker User")
                    .passwordHash("hashed")
                    .build());

            User blocked = userRepository.save(User.builder()
                    .email("blocked@test.com")
                    .username("blocked")
                    .displayName("Blocked User")
                    .passwordHash("hashed")
                    .build());

            userBanRepository.save(
                    UserBan.builder().blocker(blocker).blocked(blocked).build());

            return new User[] {blocker, blocked};
        });

        User blocker = users[0];

        // Called OUTSIDE any transaction — session is closed
        List<UserBanDto> dtos = userBanService.listBannedUsers(blocker);

        assertThat(dtos).hasSize(1);
        UserBanDto dto = dtos.get(0);
        assertThat(dto.id()).isNotNull();
        assertThat(dto.blockedId()).isEqualTo(users[1].getId());
        assertThat(dto.blockedUsername()).isEqualTo("blocked");
        assertThat(dto.blockedDisplayName()).isEqualTo("Blocked User");
        assertThat(dto.createdAt()).isNotNull();
    }
}
