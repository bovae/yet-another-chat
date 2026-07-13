package com.bovae.yac.service;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.DirectChatDto;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.RoomMapper;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DirectChatService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final FriendshipService friendshipService;
    private final UserBanService userBanService;
    private final NotificationService notificationService;
    private final RoomMapper roomMapper;

    @Transactional
    public RoomDto getOrCreateDirectChat(User userA, User userB) {
        boolean selfDm = userA.getId().equals(userB.getId());

        if (!selfDm) {
            if (!friendshipService.areFriends(userA, userB)) {
                throw new ForbiddenException("Users must be friends to start a direct chat");
            }

            if (userBanService.isBanExistsBetween(userA, userB)) {
                throw new ForbiddenException("Cannot create a direct chat — a user ban exists between these users");
            }
        }

        String roomName = selfDm
                ? savedMessagesName(userA.getId())
                : dmName(userA.getId(), userB.getId());

        Optional<Room> existing = roomRepository.findByName(roomName);
        if (existing.isPresent()) {
            return roomMapper.toDto(existing.get());
        }

        Room room = Room.builder()
                .name(roomName)
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .nextWatermark(1L)
                .build();
        // The deterministic DM name is DB-unique, so a concurrent create losing the race raises
        // DataIntegrityViolationException → 409 (R1-68); the retried getOrCreate then finds the
        // winning room, so both callers resolve to the one DIRECT room.
        room = roomRepository.save(room);

        RoomMember memberA = RoomMember.builder()
                .room(room)
                .user(userA)
                .role(RoomRole.MEMBER)
                .build();
        roomMemberRepository.save(memberA);
        notificationService.ensureMarker(userA, room);

        if (!selfDm) {
            RoomMember memberB = RoomMember.builder()
                    .room(room)
                    .user(userB)
                    .role(RoomRole.MEMBER)
                    .build();
            roomMemberRepository.save(memberB);
            notificationService.ensureMarker(userB, room);
        }

        LOG.info("Direct chat created: roomId={}, userA={}, userB={}, selfDm={}",
                room.getId(), userA.getId(), userB.getId(), selfDm);

        return roomMapper.toDto(room);
    }

    @Transactional
    public RoomDto getOrCreateSavedMessages(User user) {
        return getOrCreateDirectChat(user, user);
    }

    public List<DirectChatDto> listDirectChats(User user) {
        List<RoomMember> memberships = roomMemberRepository.findByUserWithRoomAndOwner(user);

        List<Room> directRooms = memberships.stream()
                .map(RoomMember::getRoom)
                .filter(this::isDirectChat)
                .toList();

        List<DirectChatDto> result = new ArrayList<>();
        for (Room room : directRooms) {
            List<RoomMember> roomMembers = roomMemberRepository.findByRoomWithUsers(room);
            Optional<User> otherUser = roomMembers.stream()
                    .map(RoomMember::getUser)
                    .filter(u -> !u.getId().equals(user.getId()))
                    .findFirst();

            if (otherUser.isPresent()) {
                User other = otherUser.get();
                result.add(new DirectChatDto(
                        room.getId(),
                        room.getName(),
                        other.getId(),
                        other.getUsername(),
                        other.getDisplayName(),
                        room.getCreatedAt()
                ));
            } else {
                // Self-DM (Saved Messages) — single-member DIRECT room
                result.add(new DirectChatDto(
                        room.getId(),
                        room.getName(),
                        user.getId(),
                        user.getUsername(),
                        user.getDisplayName(),
                        room.getCreatedAt()
                ));
            }
        }

        return result;
    }

    public boolean isDirectChat(Room room) {
        return room.getVisibility() == RoomVisibility.DIRECT;
    }

    // Saved Messages is identified structurally by its deterministic name, never by member count,
    // so an orphaned DM (counterpart deleted their account) is not mistaken for it (R1-31).
    private String savedMessagesName(UUID userId) {
        return "saved-messages-" + userId;
    }

    // Deterministic, collision-free DM name: full sorted UUIDs so any pair maps to exactly one name.
    private String dmName(UUID idA, UUID idB) {
        UUID lo = idA.compareTo(idB) <= 0 ? idA : idB;
        UUID hi = idA.compareTo(idB) <= 0 ? idB : idA;
        return "dm-" + lo + "-" + hi;
    }
}
