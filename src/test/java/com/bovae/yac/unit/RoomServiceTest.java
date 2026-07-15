package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.MyRoomEntry;
import com.bovae.yac.model.dto.RoomCatalogEntry;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.RoomMapper;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.UnreadMarker;
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
import com.bovae.yac.service.FileStorageService;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomService;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link RoomService}.
 *
 * <p>Validates Correctness Properties: CP 8, CP 12, CP 13.
 * <p>Requirements: 3.1, 3.2, 3.3, 3.4, 3.5.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private RoomBanRepository roomBanRepository;

    @Mock
    private RoomInvitationRepository roomInvitationRepository;

    @Mock
    private UnreadMarkerRepository unreadMarkerRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private RoomMapper roomMapper;

    @InjectMocks
    private RoomService roomService;

    private User owner;
    private User otherUser;
    private Room existingRoom;

    @BeforeEach
    void setUp() {
        owner = User.builder()
                .id(UUID.randomUUID())
                .email("owner@test.com")
                .username("owner")
                .passwordHash("$2a$10$hashedpassword")
                .build();

        otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@test.com")
                .username("other")
                .passwordHash("$2a$10$hashedpassword")
                .build();

        existingRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("existing-room")
                .description("A test room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(owner)
                .nextWatermark(1L)
                .build();
    }

    /**
     * Validates R3-04: sidebar rooms are ordered by most-recent message first,
     * with message-less rooms falling back to name order (and placed last).
     */
    @Test
    void listUserRoomsWithUnread_ordersByLastMessageRecencyThenName() {
        RoomMember alpha = membershipOf("alpha"); // oldest message
        RoomMember beta = membershipOf("beta"); // newest message
        RoomMember gamma = membershipOf("gamma"); // no messages

        when(roomMemberRepository.findByUserWithRoomAndOwner(owner)).thenReturn(List.of(alpha, beta, gamma));
        when(messageRepository.countUnreadPerRoom(eq(owner.getId()), any())).thenReturn(Collections.emptyList());
        when(messageRepository.findLastMessageInstantByRoomIds(any()))
                .thenReturn(List.of(
                        new Object[] {alpha.getRoom().getId(), Instant.parse("2026-07-01T00:00:00Z")},
                        new Object[] {beta.getRoom().getId(), Instant.parse("2026-07-10T00:00:00Z")}));

        List<String> names = roomService.listUserRoomsWithUnread(owner).stream()
                .map(entry -> entry.name())
                .toList();

        assertThat(names).containsExactly("beta", "alpha", "gamma");

        // The grouped unread query receives the actual room ids (not nulls) for every membership.
        ArgumentCaptor<List<UUID>> roomIdsCaptor = ArgumentCaptor.captor();
        verify(messageRepository).countUnreadPerRoom(eq(owner.getId()), roomIdsCaptor.capture());
        assertThat(roomIdsCaptor.getValue())
                .containsExactly(
                        alpha.getRoom().getId(),
                        beta.getRoom().getId(),
                        gamma.getRoom().getId());

        // No DIRECT rooms here, so the counterpart lookup must be skipped entirely.
        verify(roomMemberRepository, never()).findByRoomIdInWithUsers(any());
    }

    private RoomMember membershipOf(String roomName) {
        Room room = Room.builder()
                .id(UUID.randomUUID())
                .name(roomName)
                .visibility(RoomVisibility.PUBLIC)
                .owner(owner)
                .nextWatermark(1L)
                .build();
        return RoomMember.builder().room(room).user(owner).role(RoomRole.OWNER).build();
    }

    /**
     * Validates CP 12: createRoom with a unique name creates a Room with the creator
     * as Owner and a RoomMember with OWNER role.
     */
    @Test
    void createRoom_withUniqueName_createsRoomWithOwnerMember() {
        String roomName = "new-room";
        String description = "A new room";

        when(roomRepository.existsByName(roomName)).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            return new RoomDto(
                    r.getId(),
                    r.getName(),
                    r.getDescription(),
                    r.getVisibility(),
                    r.getOwner().getId(),
                    r.getOwner().getUsername(),
                    r.getNextWatermark(),
                    r.getCreatedAt());
        });

        RoomDto result = roomService.createRoom(roomName, description, RoomVisibility.PUBLIC, owner);

        assertThat(result.name()).isEqualTo(roomName);
        assertThat(result.description()).isEqualTo(description);
        assertThat(result.visibility()).isEqualTo(RoomVisibility.PUBLIC);
        assertThat(result.ownerId()).isEqualTo(owner.getId());
        assertThat(result.ownerUsername()).isEqualTo(owner.getUsername());
        assertThat(result.nextWatermark()).isEqualTo(1L);
        assertThat(result.id()).isNotNull();

        ArgumentCaptor<RoomMember> memberCaptor = ArgumentCaptor.forClass(RoomMember.class);
        verify(roomMemberRepository).save(memberCaptor.capture());
        RoomMember savedMember = memberCaptor.getValue();
        assertThat(savedMember.getUser()).isEqualTo(owner);
        assertThat(savedMember.getRole()).isEqualTo(RoomRole.OWNER);

        // The owner's unread marker is seeded so the new room starts read for its creator.
        verify(notificationService).ensureMarker(eq(owner), any(Room.class));
    }

    /**
     * Validates CP 12: createRoom with a duplicate name throws ConflictException.
     */
    @Test
    void createRoom_withDuplicateName_throwsConflictException() {
        when(roomRepository.existsByName("existing-room")).thenReturn(true);

        assertThatThrownBy(() -> roomService.createRoom("existing-room", "desc", RoomVisibility.PUBLIC, owner))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already taken");

        verify(roomRepository, never()).save(any());
        verify(roomMemberRepository, never()).save(any());
    }

    /**
     * Validates CP 8: deleteRoom by the owner cascades deletion of messages,
     * attachments, members, bans, invitations, and unread markers.
     */
    @Test
    void deleteRoom_byOwner_cascadesDeletion() {
        UUID roomId = existingRoom.getId();

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));

        // Set up cascade data
        UnreadMarker marker =
                UnreadMarker.builder().user(owner).room(existingRoom).build();
        when(unreadMarkerRepository.findByRoom(existingRoom)).thenReturn(List.of(marker));

        RoomInvitation invitation =
                RoomInvitation.builder().id(UUID.randomUUID()).build();
        when(roomInvitationRepository.findByRoom(existingRoom)).thenReturn(List.of(invitation));

        RoomBan ban = RoomBan.builder().id(UUID.randomUUID()).build();
        when(roomBanRepository.findByRoom(existingRoom)).thenReturn(List.of(ban));

        RoomMember member = RoomMember.builder()
                .room(existingRoom)
                .user(owner)
                .role(RoomRole.OWNER)
                .build();
        when(roomMemberRepository.findByRoom(existingRoom)).thenReturn(List.of(member));

        Attachment attachment = Attachment.builder().id(UUID.randomUUID()).build();
        when(attachmentRepository.findByRoom(existingRoom)).thenReturn(List.of(attachment));

        roomService.deleteRoom(roomId, owner);

        // Verify cascade order
        verify(unreadMarkerRepository).deleteAll(List.of(marker));
        verify(roomInvitationRepository).deleteAll(List.of(invitation));
        verify(roomBanRepository).deleteAll(List.of(ban));
        verify(roomMemberRepository).deleteAll(List.of(member));
        verify(attachmentRepository).deleteAll(List.of(attachment));
        verify(messageRepository).nullifyReplyToByRoom(existingRoom);
        verify(messageRepository).deleteByRoom(existingRoom);
        verify(roomRepository).delete(existingRoom);
        verify(fileStorageService).deleteRoomDirectoryAfterCommit(existingRoom.getId());
    }

    /**
     * Validates Requirement 3.4: deleteRoom by a non-owner throws ForbiddenException.
     */
    @Test
    void deleteRoom_byNonOwner_throwsForbiddenException() {
        UUID roomId = existingRoom.getId();

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));

        assertThatThrownBy(() -> roomService.deleteRoom(roomId, otherUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the room owner");

        verify(roomRepository, never()).delete(any());
        verify(messageRepository, never()).deleteByRoom(any());
    }

    /**
     * Validates CP 13: searchCatalog returns only PUBLIC rooms matching the search term.
     */
    @Test
    void searchCatalog_returnsOnlyPublicRoomsMatchingSearchTerm() {
        Room publicRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("public-chat")
                .description("A public room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(owner)
                .nextWatermark(5L)
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        Page<Room> roomPage = new PageImpl<>(List.of(publicRoom), pageable, 1);

        when(roomRepository.findByVisibilityAndNameContainingIgnoreCase(
                        eq(RoomVisibility.PUBLIC), eq("chat"), eq(pageable)))
                .thenReturn(roomPage);
        when(roomMemberRepository.countByRoom(publicRoom)).thenReturn(2L);

        Page<RoomCatalogEntry> result = roomService.searchCatalog("chat", pageable);

        assertThat(result.getContent()).hasSize(1);
        RoomCatalogEntry entry = result.getContent().get(0);
        assertThat(entry.id()).isEqualTo(publicRoom.getId());
        assertThat(entry.name()).isEqualTo("public-chat");
        assertThat(entry.description()).isEqualTo("A public room");
        assertThat(entry.memberCount()).isEqualTo(2);

        verify(roomRepository).findByVisibilityAndNameContainingIgnoreCase(RoomVisibility.PUBLIC, "chat", pageable);
    }

    /**
     * Validates CP 13: searchCatalog with no matching rooms returns empty page.
     */
    @Test
    void searchCatalog_noMatches_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Room> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

        when(roomRepository.findByVisibilityAndNameContainingIgnoreCase(
                        eq(RoomVisibility.PUBLIC), eq("nonexistent"), eq(pageable)))
                .thenReturn(emptyPage);

        Page<RoomCatalogEntry> result = roomService.searchCatalog("nonexistent", pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    // --- deleteRoom (not found) ---

    /**
     * Validates Requirement 3.4: deleting a room that does not exist throws ResourceNotFoundException.
     */
    @Test
    void deleteRoom_roomNotFound_throwsResourceNotFoundException() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.deleteRoom(roomId, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room not found");

        verify(roomRepository, never()).delete(any());
    }

    // --- getRoomByIdWithOwner / getRoomDtoById ---

    @Test
    void getRoomByIdWithOwner_returnsRoom_whenFound() {
        UUID roomId = existingRoom.getId();
        when(roomRepository.findByIdWithOwner(roomId)).thenReturn(Optional.of(existingRoom));

        Room result = roomService.getRoomByIdWithOwner(roomId);

        assertThat(result).isSameAs(existingRoom);
    }

    @Test
    void getRoomByIdWithOwner_throwsResourceNotFound_whenRoomMissing() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findByIdWithOwner(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.getRoomByIdWithOwner(roomId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room not found");
    }

    @Test
    void getRoomDtoById_returnsDto_whenFound() {
        UUID roomId = existingRoom.getId();
        when(roomRepository.findByIdWithOwner(roomId)).thenReturn(Optional.of(existingRoom));
        when(roomMapper.toDto(existingRoom)).thenReturn(toDto(existingRoom));

        RoomDto result = roomService.getRoomDtoById(roomId);

        assertThat(result.id()).isEqualTo(existingRoom.getId());
        assertThat(result.name()).isEqualTo(existingRoom.getName());
    }

    @Test
    void getRoomDtoById_throwsResourceNotFound_whenRoomMissing() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findByIdWithOwner(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.getRoomDtoById(roomId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room not found");
    }

    // --- updateRoom ---

    @Test
    void updateRoom_roomNotFound_throwsResourceNotFoundException() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.updateRoom(roomId, owner, "new-name", "desc", RoomVisibility.PUBLIC))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room not found");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void updateRoom_byNonOwner_throwsForbiddenException() {
        UUID roomId = existingRoom.getId();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));

        assertThatThrownBy(() -> roomService.updateRoom(roomId, otherUser, "new-name", "desc", RoomVisibility.PUBLIC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the room owner");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void updateRoom_directRoom_throwsForbiddenException() {
        Room directRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("dm")
                .visibility(RoomVisibility.DIRECT)
                .owner(owner)
                .nextWatermark(1L)
                .build();
        UUID roomId = directRoom.getId();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(directRoom));

        assertThatThrownBy(() -> roomService.updateRoom(roomId, owner, "new-name", "desc", RoomVisibility.PUBLIC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("DIRECT rooms cannot be modified");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void updateRoom_convertToDirect_throwsForbiddenException() {
        UUID roomId = existingRoom.getId();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));

        assertThatThrownBy(() -> roomService.updateRoom(roomId, owner, "new-name", "desc", RoomVisibility.DIRECT))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("converted to DIRECT");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void updateRoom_duplicateName_throwsConflictException() {
        UUID roomId = existingRoom.getId();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));
        when(roomRepository.existsByName("taken-name")).thenReturn(true);

        assertThatThrownBy(() -> roomService.updateRoom(roomId, owner, "taken-name", "desc", RoomVisibility.PUBLIC))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already taken");

        verify(roomRepository, never()).save(any());
    }

    @Test
    void updateRoom_allFieldsProvided_updatesNameDescriptionAndVisibility() {
        UUID roomId = existingRoom.getId();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));
        when(roomRepository.existsByName("renamed")).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> toDto(invocation.getArgument(0)));

        RoomDto result = roomService.updateRoom(roomId, owner, "renamed", "new description", RoomVisibility.PRIVATE);

        assertThat(result.name()).isEqualTo("renamed");
        assertThat(result.description()).isEqualTo("new description");
        assertThat(result.visibility()).isEqualTo(RoomVisibility.PRIVATE);
        assertThat(existingRoom.getName()).isEqualTo("renamed");
        assertThat(existingRoom.getVisibility()).isEqualTo(RoomVisibility.PRIVATE);
    }

    @Test
    void updateRoom_nullFields_leavesExistingValuesUnchanged() {
        UUID roomId = existingRoom.getId();
        String originalName = existingRoom.getName();
        String originalDescription = existingRoom.getDescription();
        RoomVisibility originalVisibility = existingRoom.getVisibility();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> toDto(invocation.getArgument(0)));

        RoomDto result = roomService.updateRoom(roomId, owner, null, null, null);

        assertThat(result.name()).isEqualTo(originalName);
        assertThat(result.description()).isEqualTo(originalDescription);
        assertThat(result.visibility()).isEqualTo(originalVisibility);
        assertThat(existingRoom.getName()).isEqualTo(originalName);
    }

    @Test
    void updateRoom_unchangedName_skipsUniquenessCheck() {
        UUID roomId = existingRoom.getId();
        String sameName = existingRoom.getName();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(existingRoom));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomMapper.toDto(any(Room.class))).thenAnswer(invocation -> toDto(invocation.getArgument(0)));

        RoomDto result = roomService.updateRoom(roomId, owner, sameName, "changed description", null);

        assertThat(result.description()).isEqualTo("changed description");
        verify(roomRepository, never()).existsByName(any());
    }

    // --- listUserRoomsWithUnread (DIRECT rooms) ---

    /**
     * DIRECT rooms resolve the DM counterpart for display; a self-DM (only the user as
     * member) leaves both counterpart fields null.
     */
    @Test
    void listUserRoomsWithUnread_directRooms_resolvesCounterpartAndLeavesSelfDmNull() {
        User bob = User.builder()
                .id(UUID.randomUUID())
                .email("bob@test.com")
                .username("bob")
                .displayName("Bob Builder")
                .passwordHash("$2a$10$hashedpassword")
                .build();

        Room dmWithBob = Room.builder()
                .id(UUID.randomUUID())
                .name("dm-bob")
                .visibility(RoomVisibility.DIRECT)
                .owner(owner)
                .nextWatermark(1L)
                .build();
        Room selfDm = Room.builder()
                .id(UUID.randomUUID())
                .name("self-dm")
                .visibility(RoomVisibility.DIRECT)
                .owner(owner)
                .nextWatermark(1L)
                .build();

        RoomMember ownerInDm = RoomMember.builder()
                .room(dmWithBob)
                .user(owner)
                .role(RoomRole.MEMBER)
                .build();
        RoomMember bobInDm = RoomMember.builder()
                .room(dmWithBob)
                .user(bob)
                .role(RoomRole.MEMBER)
                .build();
        RoomMember ownerInSelf = RoomMember.builder()
                .room(selfDm)
                .user(owner)
                .role(RoomRole.MEMBER)
                .build();

        when(roomMemberRepository.findByUserWithRoomAndOwner(owner)).thenReturn(List.of(ownerInDm, ownerInSelf));
        when(messageRepository.countUnreadPerRoom(eq(owner.getId()), any())).thenReturn(Collections.emptyList());
        when(messageRepository.findLastMessageInstantByRoomIds(any())).thenReturn(Collections.emptyList());
        when(roomMemberRepository.findByRoomIdInWithUsers(any())).thenReturn(List.of(ownerInDm, bobInDm, ownerInSelf));

        List<MyRoomEntry> entries = roomService.listUserRoomsWithUnread(owner);

        MyRoomEntry bobEntry = entries.stream()
                .filter(e -> e.id().equals(dmWithBob.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(bobEntry.otherUsername()).isEqualTo("bob");
        assertThat(bobEntry.otherDisplayName()).isEqualTo("Bob Builder");

        MyRoomEntry selfEntry = entries.stream()
                .filter(e -> e.id().equals(selfDm.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(selfEntry.otherUsername()).isNull();
        assertThat(selfEntry.otherDisplayName()).isNull();
    }

    private static RoomDto toDto(Room r) {
        return new RoomDto(
                r.getId(),
                r.getName(),
                r.getDescription(),
                r.getVisibility(),
                r.getOwner().getId(),
                r.getOwner().getUsername(),
                r.getNextWatermark(),
                r.getCreatedAt());
    }
}
