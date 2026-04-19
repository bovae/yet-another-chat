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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DirectChatService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final FriendshipService friendshipService;
    private final UserBanService userBanService;
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

        Optional<Room> existing = selfDm
                ? findExistingSavedMessages(userA)
                : findExistingDirectChat(userA, userB);
        if (existing.isPresent()) {
            return roomMapper.toDto(existing.get());
        }

        String roomName = selfDm
                ? "saved-messages-%s".formatted(userA.getId())
                : "dm-%s-%s".formatted(
                        sortedId(userA.getId(), userB.getId(), true),
                        sortedId(userA.getId(), userB.getId(), false));

        Room room = Room.builder()
                .name(roomName)
                .visibility(RoomVisibility.DIRECT)
                .owner(userA)
                .nextWatermark(1L)
                .build();
        room = roomRepository.save(room);

        RoomMember memberA = RoomMember.builder()
                .room(room)
                .user(userA)
                .role(RoomRole.MEMBER)
                .build();
        roomMemberRepository.save(memberA);

        if (!selfDm) {
            RoomMember memberB = RoomMember.builder()
                    .room(room)
                    .user(userB)
                    .role(RoomRole.MEMBER)
                    .build();
            roomMemberRepository.save(memberB);
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

    private Optional<Room> findExistingDirectChat(User userA, User userB) {
        List<RoomMember> membershipsA = roomMemberRepository.findByUser(userA);
        Set<UUID> directRoomIdsA = membershipsA.stream()
                .filter(rm -> rm.getRoom().getVisibility() == RoomVisibility.DIRECT)
                .map(rm -> rm.getRoom().getId())
                .collect(Collectors.toSet());

        if (directRoomIdsA.isEmpty()) {
            return Optional.empty();
        }

        List<RoomMember> membershipsB = roomMemberRepository.findByUser(userB);
        return membershipsB.stream()
                .filter(rm -> directRoomIdsA.contains(rm.getRoom().getId()))
                .map(RoomMember::getRoom)
                .findFirst();
    }

    private Optional<Room> findExistingSavedMessages(User user) {
        List<RoomMember> memberships = roomMemberRepository.findByUser(user);
        List<Room> directRooms = memberships.stream()
                .filter(rm -> rm.getRoom().getVisibility() == RoomVisibility.DIRECT)
                .map(RoomMember::getRoom)
                .toList();

        for (Room room : directRooms) {
            List<RoomMember> roomMembers = roomMemberRepository.findByRoom(room);
            if (roomMembers.size() == 1) {
                return Optional.of(room);
            }
        }
        return Optional.empty();
    }

    private String sortedId(UUID idA, UUID idB, boolean first) {
        int cmp = idA.compareTo(idB);
        if (first) {
            return cmp <= 0 ? idA.toString().substring(0, 8) : idB.toString().substring(0, 8);
        }
        return cmp <= 0 ? idB.toString().substring(0, 8) : idA.toString().substring(0, 8);
    }
}
