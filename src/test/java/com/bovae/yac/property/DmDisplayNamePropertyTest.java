package com.bovae.yac.property;

import com.bovae.yac.model.dto.MyRoomEntry;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UnreadMarkerRepository;
import com.bovae.yac.model.dto.RoomMapper;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for DM room entry display name resolution.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4
 */
class DmDisplayNamePropertyTest {

    private RoomMemberRepository roomMemberRepository;
    private NotificationService notificationService;
    private RoomService roomService;

    @BeforeTry
    void setUp() {
        roomMemberRepository = mock(RoomMemberRepository.class);
        notificationService = mock(NotificationService.class);

        RoomRepository roomRepository = mock(RoomRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
        RoomBanRepository roomBanRepository = mock(RoomBanRepository.class);
        RoomInvitationRepository roomInvitationRepository = mock(RoomInvitationRepository.class);
        UnreadMarkerRepository unreadMarkerRepository = mock(UnreadMarkerRepository.class);
        RoomMapper roomMapper = mock(RoomMapper.class);

        roomService = new RoomService(
                roomRepository,
                roomMemberRepository,
                messageRepository,
                attachmentRepository,
                roomBanRepository,
                roomInvitationRepository,
                unreadMarkerRepository,
                notificationService,
                roomMapper
        );

        when(notificationService.computeUnreadCount(any(User.class), any(Room.class))).thenReturn(0);
    }

    @AfterTry
    void tearDown() {
        roomMemberRepository = null;
        notificationService = null;
        roomService = null;
    }

    @Provide
    Arbitrary<String> usernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> displayNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Provide
    Arbitrary<RoomVisibility> nonDirectVisibilities() {
        return Arbitraries.of(RoomVisibility.PUBLIC, RoomVisibility.PRIVATE);
    }

    private User buildUser(String username, String displayName) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(username + "@test.com")
                .username(username)
                .displayName(displayName)
                .passwordHash("hashed")
                .build();
    }

    private Room buildRoom(String name, RoomVisibility visibility, User owner) {
        return Room.builder()
                .id(UUID.randomUUID())
                .name(name)
                .visibility(visibility)
                .owner(owner)
                .nextWatermark(1L)
                .build();
    }

    private RoomMember buildMember(Room room, User user, RoomRole role) {
        return RoomMember.builder()
                .room(room)
                .user(user)
                .role(role)
                .build();
    }

    /**
     * Property 2: DM room entry display name resolution — two-member DIRECT room
     *
     * For any MyRoomEntry with visibility == DIRECT and two distinct members,
     * otherUsername SHALL be non-null and equal to the other member's username,
     * and otherDisplayName SHALL equal the other member's display name (or null if unset).
     *
     * Validates: Requirements 3.1, 3.2, 3.3, 3.4
     */
    @Property(tries = 20)
    void directRoomWithTwoMembers_shallPopulateOtherUserFields(
            @ForAll("usernames") String currentUsername,
            @ForAll("usernames") String otherUsername,
            @ForAll("displayNames") String otherDisplayName
    ) {
        // Ensure distinct usernames
        if (currentUsername.equals(otherUsername)) {
            return;
        }

        User currentUser = buildUser(currentUsername, null);
        User otherUser = buildUser(otherUsername, otherDisplayName);
        Room room = buildRoom("dm-" + currentUsername + "-" + otherUsername, RoomVisibility.DIRECT, currentUser);

        RoomMember currentMember = buildMember(room, currentUser, RoomRole.MEMBER);
        RoomMember otherMember = buildMember(room, otherUser, RoomRole.MEMBER);

        when(roomMemberRepository.findByUserWithRoomAndOwner(currentUser))
                .thenReturn(List.of(currentMember));
        when(roomMemberRepository.findByRoomWithUsers(room))
                .thenReturn(List.of(currentMember, otherMember));

        List<MyRoomEntry> entries = roomService.listUserRoomsWithUnread(currentUser);

        assertThat(entries).hasSize(1);
        MyRoomEntry entry = entries.getFirst();
        assertThat(entry.visibility()).isEqualTo(RoomVisibility.DIRECT);
        assertThat(entry.otherUsername()).isEqualTo(otherUsername);
        assertThat(entry.otherDisplayName()).isEqualTo(otherDisplayName);
    }

    /**
     * Property 2: DM room entry display name resolution — two-member DIRECT room, no display name
     *
     * When the other member has no display name set, otherDisplayName SHALL be null.
     *
     * Validates: Requirements 3.1, 3.2, 3.3
     */
    @Property(tries = 20)
    void directRoomWithTwoMembers_nullDisplayName_shallReturnNullDisplayName(
            @ForAll("usernames") String currentUsername,
            @ForAll("usernames") String otherUsername
    ) {
        if (currentUsername.equals(otherUsername)) {
            return;
        }

        User currentUser = buildUser(currentUsername, null);
        User otherUser = buildUser(otherUsername, null); // no display name
        Room room = buildRoom("dm-" + currentUsername + "-" + otherUsername, RoomVisibility.DIRECT, currentUser);

        RoomMember currentMember = buildMember(room, currentUser, RoomRole.MEMBER);
        RoomMember otherMember = buildMember(room, otherUser, RoomRole.MEMBER);

        when(roomMemberRepository.findByUserWithRoomAndOwner(currentUser))
                .thenReturn(List.of(currentMember));
        when(roomMemberRepository.findByRoomWithUsers(room))
                .thenReturn(List.of(currentMember, otherMember));

        List<MyRoomEntry> entries = roomService.listUserRoomsWithUnread(currentUser);

        assertThat(entries).hasSize(1);
        MyRoomEntry entry = entries.getFirst();
        assertThat(entry.otherUsername()).isEqualTo(otherUsername);
        assertThat(entry.otherDisplayName()).isNull();
    }

    /**
     * Property 2: DM room entry display name resolution — self-DM (single member)
     *
     * If the room is a self-DM (single member), both otherUsername and otherDisplayName SHALL be null.
     *
     * Validates: Requirements 3.1, 3.2, 3.3
     */
    @Property(tries = 20)
    void selfDmRoom_shallHaveNullOtherFields(
            @ForAll("usernames") String username
    ) {
        User user = buildUser(username, "Some Display Name");
        Room room = buildRoom("dm-self-" + username, RoomVisibility.DIRECT, user);

        RoomMember selfMember = buildMember(room, user, RoomRole.MEMBER);

        when(roomMemberRepository.findByUserWithRoomAndOwner(user))
                .thenReturn(List.of(selfMember));
        when(roomMemberRepository.findByRoomWithUsers(room))
                .thenReturn(List.of(selfMember));

        List<MyRoomEntry> entries = roomService.listUserRoomsWithUnread(user);

        assertThat(entries).hasSize(1);
        MyRoomEntry entry = entries.getFirst();
        assertThat(entry.visibility()).isEqualTo(RoomVisibility.DIRECT);
        assertThat(entry.otherUsername()).isNull();
        assertThat(entry.otherDisplayName()).isNull();
    }

    /**
     * Property 2: DM room entry display name resolution — non-DIRECT room
     *
     * For any entry with visibility != DIRECT, both otherUsername and otherDisplayName SHALL be null.
     *
     * Validates: Requirements 3.1, 3.2
     */
    @Property(tries = 20)
    void nonDirectRoom_shallHaveNullOtherFields(
            @ForAll("usernames") String username,
            @ForAll("nonDirectVisibilities") RoomVisibility visibility
    ) {
        User user = buildUser(username, null);
        Room room = buildRoom("room-" + username, visibility, user);

        RoomMember member = buildMember(room, user, RoomRole.OWNER);

        when(roomMemberRepository.findByUserWithRoomAndOwner(user))
                .thenReturn(List.of(member));

        List<MyRoomEntry> entries = roomService.listUserRoomsWithUnread(user);

        assertThat(entries).hasSize(1);
        MyRoomEntry entry = entries.getFirst();
        assertThat(entry.visibility()).isEqualTo(visibility);
        assertThat(entry.otherUsername()).isNull();
        assertThat(entry.otherDisplayName()).isNull();
    }
}
