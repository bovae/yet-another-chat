package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.RoomCatalogEntry;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for RoomService and RoomMemberService: room creation invariants,
 * catalog visibility filtering, join access control, and membership lifecycle.
 *
 * Validates: Requirements 8.1, 8.2, 8.3, 8.4, 9.1, 9.2, 9.3, 9.4, 10.1, 10.3, 10.4, 11.1, 11.2, 11.5
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class RoomPropertyTest {

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

    @AfterTry
    void cleanup() {
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
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
    Arbitrary<String> roomDescriptions() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(0)
                .ofMaxLength(50);
    }

    @Provide
    Arbitrary<RoomVisibility> roomVisibilities() {
        return Arbitraries.of(RoomVisibility.PUBLIC, RoomVisibility.PRIVATE, RoomVisibility.DIRECT);
    }

    // Feature: online-chat-server, Property 12: Room creation invariants
    /**
     * Validates: Requirements 8.1, 8.2, 8.3, 8.4
     *
     * For any valid room creation request with a unique name, the system SHALL create the Room,
     * assign the creator as Owner, and add a RoomMember record with OWNER role.
     * The room SHALL have exactly one Owner at all times.
     * Duplicate name SHALL throw ConflictException.
     */
    @Property(tries = 100)
    void roomCreationInvariants(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll("roomNames") String roomName,
            @ForAll("roomDescriptions") String description,
            @ForAll("roomVisibilities") RoomVisibility visibility
    ) {
        // Register a user to act as room creator
        User creator = userRepository.findById(userService.register(email, username, password).id()).orElseThrow();

        // Create a room with a unique name
        String uniqueName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueName, description, visibility, creator).id());

        // Room SHALL be persisted
        assertThat(roomRepository.findById(room.getId())).isPresent();

        // Room SHALL have the correct properties
        Room persisted = roomRepository.findById(room.getId()).orElseThrow();
        assertThat(persisted.getName()).isEqualTo(uniqueName);
        assertThat(persisted.getVisibility()).isEqualTo(visibility);
        assertThat(persisted.getOwner().getId()).isEqualTo(creator.getId());
        assertThat(persisted.getNextWatermark()).isEqualTo(1L);

        // A RoomMember record with OWNER role SHALL exist for the creator
        List<RoomMember> members = roomMemberRepository.findByRoom(room);
        assertThat(members).hasSize(1);

        RoomMember ownerMember = members.get(0);
        assertThat(ownerMember.getUser().getId()).isEqualTo(creator.getId());
        assertThat(ownerMember.getRole()).isEqualTo(RoomRole.OWNER);

        // Exactly one OWNER at all times
        long ownerCount = members.stream()
                .filter(m -> m.getRole() == RoomRole.OWNER)
                .count();
        assertThat(ownerCount).isEqualTo(1);

        // Duplicate name SHALL throw ConflictException
        assertThatThrownBy(() -> roomService.createRoom(uniqueName, "other desc", visibility, creator))
                .isInstanceOf(ConflictException.class);

        // No additional room should have been created
        assertThat(roomRepository.findById(room.getId())).isPresent();
    }

    // Feature: online-chat-server, Property 13: Room catalog visibility filtering
    /**
     * Validates: Requirements 9.1, 9.2, 10.1
     *
     * For any set of Rooms with mixed visibility (PUBLIC, PRIVATE, DIRECT), the public catalog
     * SHALL return exactly the PUBLIC rooms and SHALL exclude all PRIVATE and DIRECT rooms.
     * Search filtering SHALL return only rooms whose name matches the search term.
     */
    @Property(tries = 20)
    void roomCatalogVisibilityFiltering(
            @ForAll("validEmails") String email,
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password
    ) {
        // Register a user to own the rooms
        User owner = userRepository.findById(userService.register(email, username, password).id()).orElseThrow();

        // Create rooms with each visibility type using unique prefixes for search
        String prefix = UUID.randomUUID().toString().substring(0, 8);

        Room publicRoom1 = roomService.getRoomById(roomService.createRoom(prefix + "-pub1", "Public room 1", RoomVisibility.PUBLIC, owner).id());
        Room publicRoom2 = roomService.getRoomById(roomService.createRoom(prefix + "-pub2", "Public room 2", RoomVisibility.PUBLIC, owner).id());

        // Create PRIVATE and DIRECT rooms directly via repository (RoomService.createRoom
        // always adds an OWNER member, which is fine)
        Room privateRoom = roomService.getRoomById(roomService.createRoom(prefix + "-priv", "Private room", RoomVisibility.PRIVATE, owner).id());
        Room directRoom = roomService.getRoomById(roomService.createRoom(prefix + "-direct", "Direct room", RoomVisibility.DIRECT, owner).id());

        // Search catalog with the prefix — should return only PUBLIC rooms
        Page<RoomCatalogEntry> catalogPage = roomService.searchCatalog(prefix, PageRequest.of(0, 50));
        List<RoomCatalogEntry> catalogEntries = catalogPage.getContent();

        // SHALL return exactly the PUBLIC rooms
        assertThat(catalogEntries).hasSize(2);

        List<UUID> catalogIds = catalogEntries.stream()
                .map(RoomCatalogEntry::id)
                .toList();
        assertThat(catalogIds).containsExactlyInAnyOrder(publicRoom1.getId(), publicRoom2.getId());

        // SHALL exclude PRIVATE and DIRECT rooms
        assertThat(catalogIds).doesNotContain(privateRoom.getId(), directRoom.getId());

        // Search filtering SHALL return only rooms whose name matches the search term
        Page<RoomCatalogEntry> filteredPage = roomService.searchCatalog(prefix + "-pub1", PageRequest.of(0, 50));
        List<RoomCatalogEntry> filteredEntries = filteredPage.getContent();

        assertThat(filteredEntries).hasSize(1);
        assertThat(filteredEntries.get(0).id()).isEqualTo(publicRoom1.getId());

        // A search term that matches no rooms SHALL return empty
        Page<RoomCatalogEntry> emptyPage = roomService.searchCatalog("nonexistent-" + UUID.randomUUID(), PageRequest.of(0, 50));
        assertThat(emptyPage.getContent()).isEmpty();
    }

    // Feature: online-chat-server, Property 14: Room join access control
    /**
     * Validates: Requirements 9.3, 9.4, 10.3, 10.4
     *
     * Joining a PUBLIC room SHALL succeed if and only if no RoomBan exists for that User.
     * Joining a PRIVATE room SHALL succeed if and only if a RoomInvitation exists.
     * Accepting an invitation SHALL add the User as Member and delete the RoomInvitation.
     */
    @Property(tries = 20)
    void roomJoinAccessControl(
            @ForAll("validEmails") String ownerEmail,
            @ForAll("validUsernames") String ownerUsername,
            @ForAll("validPasswords") String ownerPassword,
            @ForAll("validEmails") String joinerEmail,
            @ForAll("validUsernames") String joinerUsername,
            @ForAll("validPasswords") String joinerPassword
    ) {
        // Register owner and joiner with unique identifiers
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        User owner = userRepository.findById(userService.register(ownerEmail + suffix, ownerUsername + suffix, ownerPassword).id()).orElseThrow();
        User joiner = userRepository.findById(userService.register(joinerEmail + suffix + "j", joinerUsername + suffix + "j", joinerPassword).id()).orElseThrow();

        // --- PUBLIC room: join succeeds when no ban exists ---
        String publicRoomName = "pub-" + UUID.randomUUID();
        Room publicRoom = roomService.getRoomById(roomService.createRoom(publicRoomName, "Public room", RoomVisibility.PUBLIC, owner).id());

        RoomMember joinedMember = roomMemberService.joinPublicRoom(publicRoom, joiner);
        assertThat(joinedMember).isNotNull();
        assertThat(joinedMember.getRole()).isEqualTo(RoomRole.MEMBER);
        assertThat(roomMemberService.isMember(publicRoom, joiner)).isTrue();

        // Clean up the membership for the next sub-test
        roomMemberService.leaveRoom(publicRoom, joiner);

        // --- PUBLIC room: join fails when RoomBan exists ---
        RoomBan ban = RoomBan.builder()
                .room(publicRoom)
                .user(joiner)
                .bannedBy(owner)
                .build();
        roomBanRepository.save(ban);

        assertThatThrownBy(() -> roomMemberService.joinPublicRoom(publicRoom, joiner))
                .isInstanceOf(ForbiddenException.class);
        assertThat(roomMemberService.isMember(publicRoom, joiner)).isFalse();

        // Clean up ban
        roomBanRepository.delete(ban);

        // --- PRIVATE room: join fails without invitation ---
        String privateRoomName = "priv-" + UUID.randomUUID();
        Room privateRoom = roomService.getRoomById(roomService.createRoom(privateRoomName, "Private room", RoomVisibility.PRIVATE, owner).id());

        assertThatThrownBy(() -> roomMemberService.joinPrivateRoomViaInvitation(privateRoom, joiner))
                .isInstanceOf(ForbiddenException.class);
        assertThat(roomMemberService.isMember(privateRoom, joiner)).isFalse();

        // --- PRIVATE room: join succeeds with invitation, invitation is deleted ---
        RoomInvitation invitation = RoomInvitation.builder()
                .room(privateRoom)
                .inviter(owner)
                .invitee(joiner)
                .build();
        invitation = roomInvitationRepository.save(invitation);

        RoomMember privateJoinedMember = roomMemberService.joinPrivateRoomViaInvitation(privateRoom, joiner);
        assertThat(privateJoinedMember).isNotNull();
        assertThat(privateJoinedMember.getRole()).isEqualTo(RoomRole.MEMBER);
        assertThat(roomMemberService.isMember(privateRoom, joiner)).isTrue();

        // Invitation SHALL be deleted after acceptance
        assertThat(roomInvitationRepository.findByRoomAndInvitee(privateRoom, joiner)).isEmpty();
    }

    // Feature: online-chat-server, Property 15: Room membership lifecycle
    /**
     * Validates: Requirements 11.1, 11.2, 11.5
     *
     * For any non-Owner Member, leaving a Room SHALL remove the RoomMember record.
     * For any Owner, attempting to leave SHALL be rejected (ForbiddenException).
     */
    @Property(tries = 20)
    void roomMembershipLifecycle(
            @ForAll("validEmails") String ownerEmail,
            @ForAll("validUsernames") String ownerUsername,
            @ForAll("validPasswords") String ownerPassword,
            @ForAll("validEmails") String memberEmail,
            @ForAll("validUsernames") String memberUsername,
            @ForAll("validPasswords") String memberPassword
    ) {
        // Register owner and member with unique identifiers
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        User owner = userRepository.findById(userService.register(ownerEmail + suffix, ownerUsername + suffix, ownerPassword).id()).orElseThrow();
        User member = userRepository.findById(userService.register(memberEmail + suffix + "m", memberUsername + suffix + "m", memberPassword).id()).orElseThrow();

        // Create a public room and have the member join
        String roomName = "lifecycle-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(roomName, "Lifecycle test room", RoomVisibility.PUBLIC, owner).id());
        roomMemberService.joinPublicRoom(room, member);

        // Verify both are members
        assertThat(roomMemberService.isMember(room, owner)).isTrue();
        assertThat(roomMemberService.isMember(room, member)).isTrue();

        // --- Non-Owner Member leaves: RoomMember record SHALL be removed ---
        roomMemberService.leaveRoom(room, member);
        assertThat(roomMemberService.isMember(room, member)).isFalse();

        // --- Owner attempts to leave: SHALL be rejected with ForbiddenException ---
        assertThatThrownBy(() -> roomMemberService.leaveRoom(room, owner))
                .isInstanceOf(ForbiddenException.class);

        // Owner SHALL still be a member after the rejected leave attempt
        assertThat(roomMemberService.isMember(room, owner)).isTrue();
    }
}
