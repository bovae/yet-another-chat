package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
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
 * Property-based tests for ModerationService: admin moderation actions including
 * kick/ban/unban, message deletion, role grant/revoke, and owner protection.
 *
 * Validates: Requirements 12.1, 12.3, 12.4, 12.6, 12.7, 12.8
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class ModerationPropertyTest {

    @Autowired
    private ModerationService moderationService;

    @Autowired
    private RoomService roomService;

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
    private MessageRepository messageRepository;

    @AfterTry
    void cleanup() {
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        messageRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
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
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(30)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> messageContents() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(100);
    }

    // Feature: online-chat-server, Property 16: Admin moderation actions
    /**
     * Validates: Requirements 12.1, 12.3, 12.4, 12.6, 12.7, 12.8
     *
     * For any Admin action to remove a Member, the system SHALL create a RoomBan and delete
     * the RoomMember. Removing a ban SHALL delete the RoomBan. Admin message deletion SHALL
     * permanently remove the Message. Demoting the Owner SHALL be rejected. The Owner SHALL
     * be able to grant and revoke Admin role for any Member. An Admin SHALL be able to demote
     * another non-Owner Admin to Member.
     */
    @Property(tries = 4)
    void adminModerationActions(
            @ForAll("validEmails") String ownerEmail,
            @ForAll("validUsernames") String ownerUsername,
            @ForAll("validPasswords") String ownerPassword,
            @ForAll("validEmails") String memberEmail,
            @ForAll("validUsernames") String memberUsername,
            @ForAll("validPasswords") String memberPassword,
            @ForAll("validEmails") String adminEmail,
            @ForAll("validUsernames") String adminUsername,
            @ForAll("validPasswords") String adminPassword,
            @ForAll("roomNames") String roomName,
            @ForAll("messageContents") String messageContent
    ) {
        // Create unique identifiers to avoid collisions
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto ownerDto = userService.register(ownerEmail + suffix, ownerUsername + suffix, ownerPassword);
        User owner = userRepository.findById(ownerDto.id()).orElseThrow();
        UserDto memberDto = userService.register(memberEmail + suffix + "m", memberUsername + suffix + "m", memberPassword);
        User member = userRepository.findById(memberDto.id()).orElseThrow();
        UserDto adminDto = userService.register(adminEmail + suffix + "a", adminUsername + suffix + "a", adminPassword);
        User admin = userRepository.findById(adminDto.id()).orElseThrow();

        // Create a public room with the owner
        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Moderation test room", RoomVisibility.PUBLIC, owner).id());

        // Add member and admin to the room
        roomMemberService.joinPublicRoom(room, member);
        roomMemberService.joinPublicRoom(room, admin);

        // Create a message from the member for later deletion test
        Message message = Message.builder()
                .room(room)
                .sender(member)
                .content(messageContent)
                .edited(false)
                .watermark(0L)
                .build();
        message = messageRepository.save(message);

        // --- Step 1: Owner grants admin role to admin user → verify ADMIN role ---
        moderationService.grantAdminRole(room, owner, admin);

        RoomMember adminMember = roomMemberRepository.findById(new RoomMemberId(room.getId(), admin.getId()))
                .orElseThrow();
        assertThat(adminMember.getRole()).isEqualTo(RoomRole.ADMIN);

        // --- Step 2: Owner kicks member → verify RoomBan created, RoomMember removed ---
        moderationService.kickMember(room, owner, member);

        assertThat(roomBanRepository.existsByRoomAndUser(room, member)).isTrue();
        assertThat(roomMemberRepository.existsByRoomAndUser(room, member)).isFalse();

        // --- Step 3: Owner unbans member → verify RoomBan deleted ---
        moderationService.unbanUserFromRoom(room, owner, member);

        assertThat(roomBanRepository.existsByRoomAndUser(room, member)).isFalse();

        // --- Step 4: Owner deletes message → verify message gone ---
        UUID messageId = message.getId();
        moderationService.deleteMessage(room, owner, messageId);

        assertThat(messageRepository.findById(messageId)).isEmpty();

        // --- Step 5: Try to demote owner → ForbiddenException ---
        assertThatThrownBy(() -> moderationService.revokeAdminRole(room, admin, owner))
                .isInstanceOf(ForbiddenException.class);

        // Owner SHALL still have OWNER role after the rejected demotion attempt
        RoomMember ownerMember = roomMemberRepository.findById(new RoomMemberId(room.getId(), owner.getId()))
                .orElseThrow();
        assertThat(ownerMember.getRole()).isEqualTo(RoomRole.OWNER);

        // --- Step 6: Owner grants admin to a re-added member, then admin demotes that admin ---
        // Re-add member to the room first
        roomMemberService.joinPublicRoom(room, member);
        moderationService.grantAdminRole(room, owner, member);

        RoomMember promotedMember = roomMemberRepository.findById(new RoomMemberId(room.getId(), member.getId()))
                .orElseThrow();
        assertThat(promotedMember.getRole()).isEqualTo(RoomRole.ADMIN);

        // Admin demotes another admin (member) → verify MEMBER role
        moderationService.revokeAdminRole(room, admin, member);

        RoomMember demotedMember = roomMemberRepository.findById(new RoomMemberId(room.getId(), member.getId()))
                .orElseThrow();
        assertThat(demotedMember.getRole()).isEqualTo(RoomRole.MEMBER);
    }
}
