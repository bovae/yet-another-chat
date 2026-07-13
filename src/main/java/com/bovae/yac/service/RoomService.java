package com.bovae.yac.service;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.MyRoomEntry;
import com.bovae.yac.model.dto.RoomCatalogEntry;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.RoomMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    private static final int UNREAD_DISPLAY_CAP = 999;

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final RoomBanRepository roomBanRepository;
    private final RoomInvitationRepository roomInvitationRepository;
    private final UnreadMarkerRepository unreadMarkerRepository;
    private final NotificationService notificationService;
    private final FileStorageService fileStorageService;
    private final RoomMapper roomMapper;

    @Transactional
    public RoomDto createRoom(String name, String description, RoomVisibility visibility, User owner) {
        if (roomRepository.existsByName(name)) {
            throw new ConflictException("Room name is already taken: %s".formatted(name));
        }

        Room room = Room.builder()
                .name(name)
                .description(description)
                .visibility(visibility)
                .owner(owner)
                .nextWatermark(1L)
                .build();

        room = roomRepository.save(room);

        RoomMember ownerMember = RoomMember.builder()
                .room(room)
                .user(owner)
                .role(RoomRole.OWNER)
                .build();

        roomMemberRepository.save(ownerMember);
        notificationService.ensureMarker(owner, room);

        LOG.info("Created room: name={}, visibility={}, owner={}, id={}",
                room.getName(), room.getVisibility(), owner.getUsername(), room.getId());

        return roomMapper.toDto(room);
    }

    @Transactional
    public void deleteRoom(UUID roomId, User requestingUser) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: %s".formatted(roomId)));

        if (!room.getOwner().getId().equals(requestingUser.getId())) {
            throw new ForbiddenException("Only the room owner can delete the room");
        }

        LOG.info("Deleting room: name={}, id={}, requestedBy={}",
                room.getName(), room.getId(), requestingUser.getUsername());

        deleteRoomCascade(room);
    }

    @Transactional(readOnly = true)
    public Page<RoomCatalogEntry> searchCatalog(String searchTerm, Pageable pageable) {
        Page<Room> rooms = roomRepository.findByVisibilityAndNameContainingIgnoreCase(
                RoomVisibility.PUBLIC, searchTerm, pageable);

        return rooms.map(room -> new RoomCatalogEntry(
                room.getId(),
                room.getName(),
                room.getDescription(),
                (int) roomMemberRepository.countByRoom(room) // count query, no member hydration (R1-45)
        ));
    }

    @Transactional(readOnly = true)
    public Room getRoomById(UUID roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: %s".formatted(roomId)));
    }

    @Transactional(readOnly = true)
    public RoomDto getRoomDtoById(UUID roomId) {
        return roomRepository.findByIdWithOwner(roomId)
                .map(roomMapper::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: %s".formatted(roomId)));
    }

    @Transactional
    public RoomDto updateRoom(UUID roomId, User owner, String name, String description, RoomVisibility visibility) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: %s".formatted(roomId)));

        if (!room.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenException("Only the room owner can update the room");
        }

        if (room.getVisibility() == RoomVisibility.DIRECT) {
            throw new ForbiddenException("DIRECT rooms cannot be modified through room settings");
        }
        if (visibility == RoomVisibility.DIRECT) {
            throw new ForbiddenException("Rooms cannot be converted to DIRECT visibility");
        }

        if (name != null && !name.equals(room.getName()) && roomRepository.existsByName(name)) {
            throw new ConflictException("Room name is already taken: %s".formatted(name));
        }

        if (name != null) {
            room.setName(name);
        }
        if (description != null) {
            room.setDescription(description);
        }
        if (visibility != null) {
            room.setVisibility(visibility);
        }

        room = roomRepository.save(room);

        LOG.info("Room updated: roomId={}, updatedBy={}", roomId, owner.getUsername());

        return roomMapper.toDto(room);
    }

    @Transactional(readOnly = true)
    public List<MyRoomEntry> listUserRoomsWithUnread(User user) {
        List<RoomMember> memberships = roomMemberRepository.findByUserWithRoomAndOwner(user);
        if (memberships.isEmpty()) {
            return List.of();
        }

        List<UUID> roomIds = memberships.stream().map(m -> m.getRoom().getId()).toList();

        // Unread counts for every room in one grouped query instead of per-room (R1-46).
        Map<UUID, Integer> unreadByRoom = new HashMap<>();
        for (Object[] row : messageRepository.countUnreadPerRoom(user.getId(), roomIds)) {
            unreadByRoom.put((UUID) row[0], Math.min(((Number) row[1]).intValue(), UNREAD_DISPLAY_CAP));
        }

        // DM counterparts for all DIRECT rooms in a single query.
        List<UUID> directRoomIds = memberships.stream()
                .map(RoomMember::getRoom)
                .filter(r -> r.getVisibility() == RoomVisibility.DIRECT)
                .map(Room::getId)
                .toList();
        Map<UUID, User> counterpartByRoom = new HashMap<>();
        if (!directRoomIds.isEmpty()) {
            for (RoomMember rm : roomMemberRepository.findByRoomIdInWithUsers(directRoomIds)) {
                if (!rm.getUser().getId().equals(user.getId())) {
                    counterpartByRoom.put(rm.getRoom().getId(), rm.getUser());
                }
            }
        }

        return memberships.stream()
                .map(membership -> {
                    Room room = membership.getRoom();
                    String otherUsername = null;
                    String otherDisplayName = null;
                    if (room.getVisibility() == RoomVisibility.DIRECT) {
                        User other = counterpartByRoom.get(room.getId());
                        if (other != null) {
                            otherUsername = other.getUsername();
                            otherDisplayName = other.getDisplayName();
                        }
                        // Self-DM (single member) leaves both null
                    }
                    return new MyRoomEntry(room.getId(), room.getName(), room.getVisibility(),
                            unreadByRoom.getOrDefault(room.getId(), 0), otherUsername, otherDisplayName);
                })
                .toList();
    }

    @Transactional
    public void deleteRoomCascade(Room room) {
        LOG.debug("Cascading delete for room: name={}, id={}", room.getName(), room.getId());

        // Delete unread markers for this room
        unreadMarkerRepository.deleteAll(unreadMarkerRepository.findByRoom(room));

        // Delete invitations for this room
        roomInvitationRepository.deleteAll(roomInvitationRepository.findByRoom(room));

        // Delete room bans for this room
        roomBanRepository.deleteAll(roomBanRepository.findByRoom(room));

        // Delete room members for this room
        roomMemberRepository.deleteAll(roomMemberRepository.findByRoom(room));

        // Delete attachments for messages in this room
        attachmentRepository.deleteAll(attachmentRepository.findByRoom(room));

        // Nullify reply-to references before deleting messages (self-referencing FK)
        messageRepository.nullifyReplyToByRoom(room);

        // Delete messages for this room
        messageRepository.deleteByRoom(room);

        // Delete the room itself
        roomRepository.delete(room);

        // Remove the room's uploaded files from disk once the transaction commits (R1-16).
        fileStorageService.deleteRoomDirectoryAfterCommit(room.getId());
    }
}
