package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for account deletion cascade behavior.
 *
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4, 11.3, 11.4
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class AccountDeletionPropertyTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomBanRepository roomBanRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UnreadMarkerRepository unreadMarkerRepository;

    @Autowired
    private UserBanRepository userBanRepository;

    @AfterTry
    void cleanup() {
        attachmentRepository.deleteAll();
        messageRepository.deleteAll();
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        unreadMarkerRepository.deleteAll();
        roomRepository.deleteAll();
        friendshipRepository.deleteAll();
        userBanRepository.deleteAll();
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
    Arbitrary<Integer> ownedRoomCounts() {
        return Arbitraries.integers().between(1, 3);
    }

    @Provide
    Arbitrary<Integer> messagesPerRoom() {
        return Arbitraries.integers().between(1, 3);
    }

    // Feature: online-chat-server, Property 8: Account deletion cascade
    @Property(tries = 20)
    void accountDeletionShallCascadeCorrectly(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("ownedRoomCounts") int ownedRoomCount,
            @ForAll("messagesPerRoom") int msgPerRoom
    ) {
        // --- Setup ---

        // 1. Register the target user (the one who will be deleted)
        UserDto targetUserDto = userService.register(email, username, password);
        User targetUser = userRepository.findById(targetUserDto.id()).orElseThrow();

        // 2. Register a second user who owns a separate room
        String otherEmail = "other" + email;
        String otherUsername = "o" + username;
        UserDto otherUserDto = userService.register(otherEmail, otherUsername, password);
        User otherUser = userRepository.findById(otherUserDto.id()).orElseThrow();

        // 3. Create rooms owned by the target user, add messages and attachments
        List<UUID> ownedRoomIds = new ArrayList<>();
        List<UUID> ownedRoomMessageIds = new ArrayList<>();
        List<UUID> ownedRoomAttachmentIds = new ArrayList<>();

        for (int r = 0; r < ownedRoomCount; r++) {
            Room room = roomRepository.save(Room.builder()
                    .name("room-" + UUID.randomUUID())
                    .visibility(RoomVisibility.PUBLIC)
                    .owner(targetUser)
                    .nextWatermark(1L)
                    .build());
            ownedRoomIds.add(room.getId());

            // Add target user as OWNER member
            roomMemberRepository.save(RoomMember.builder()
                    .room(room)
                    .user(targetUser)
                    .role(RoomRole.OWNER)
                    .build());

            // Add other user as MEMBER in the target user's room
            roomMemberRepository.save(RoomMember.builder()
                    .room(room)
                    .user(otherUser)
                    .role(RoomRole.MEMBER)
                    .build());

            // Add a room ban
            roomBanRepository.save(RoomBan.builder()
                    .room(room)
                    .user(otherUser)
                    .bannedBy(targetUser)
                    .build());

            // Add an invitation
            roomInvitationRepository.save(RoomInvitation.builder()
                    .room(room)
                    .inviter(targetUser)
                    .invitee(otherUser)
                    .build());

            // Add messages with attachments
            for (int m = 0; m < msgPerRoom; m++) {
                long watermark = room.getNextWatermark();
                room.setNextWatermark(watermark + 1);
                roomRepository.save(room);

                Message msg = messageRepository.save(Message.builder()
                        .room(room)
                        .sender(targetUser)
                        .content("Message " + m)
                        .watermark(watermark)
                        .edited(false)
                        .build());
                ownedRoomMessageIds.add(msg.getId());

                Attachment att = attachmentRepository.save(Attachment.builder()
                        .message(msg)
                        .originalFileName("file" + m + ".txt")
                        .storagePath("/tmp/file" + m + ".txt")
                        .fileSize(100L)
                        .contentType("text/plain")
                        .build());
                ownedRoomAttachmentIds.add(att.getId());
            }
        }

        // 4. Create a room owned by the other user, add target user as member
        Room otherRoom = roomRepository.save(Room.builder()
                .name("other-room-" + UUID.randomUUID())
                .visibility(RoomVisibility.PUBLIC)
                .owner(otherUser)
                .nextWatermark(1L)
                .build());
        UUID otherRoomId = otherRoom.getId();

        roomMemberRepository.save(RoomMember.builder()
                .room(otherRoom)
                .user(otherUser)
                .role(RoomRole.OWNER)
                .build());

        roomMemberRepository.save(RoomMember.builder()
                .room(otherRoom)
                .user(targetUser)
                .role(RoomRole.MEMBER)
                .build());

        // Verify setup: target user is a member of the other room
        assertThat(roomMemberRepository.existsByRoomAndUser(otherRoom, targetUser)).isTrue();

        // --- Act: Delete the target user's account ---
        userService.deleteAccount(targetUser.getId());

        // --- Verify ---

        // 1. User record is gone
        assertThat(userRepository.findById(targetUser.getId())).isEmpty();

        // 2. All owned rooms are gone
        for (UUID roomId : ownedRoomIds) {
            assertThat(roomRepository.findById(roomId)).isEmpty();
        }

        // 3. All messages in owned rooms are gone
        for (UUID msgId : ownedRoomMessageIds) {
            assertThat(messageRepository.findById(msgId)).isEmpty();
        }

        // 4. All attachments in owned rooms are gone
        for (UUID attId : ownedRoomAttachmentIds) {
            assertThat(attachmentRepository.findById(attId)).isEmpty();
        }

        // 5. Target user's membership in other rooms is removed
        assertThat(roomMemberRepository.existsByRoomAndUser(otherRoom, targetUser)).isFalse();

        // 6. The other room still exists (not deleted)
        assertThat(roomRepository.findById(otherRoomId)).isPresent();

        // 7. The other user still exists
        assertThat(userRepository.findById(otherUser.getId())).isPresent();

        // 8. The other user is still a member of their own room
        assertThat(roomMemberRepository.existsByRoomAndUser(otherRoom, otherUser)).isTrue();
    }
}
