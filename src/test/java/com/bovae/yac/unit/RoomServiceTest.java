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
}
