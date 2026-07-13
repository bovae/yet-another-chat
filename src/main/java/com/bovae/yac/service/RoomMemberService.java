package com.bovae.yac.service;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.dto.RoomMemberMapper;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoomMemberService {

    private final RoomMemberRepository roomMemberRepository;
    private final RoomBanRepository roomBanRepository;
    private final RoomInvitationRepository roomInvitationRepository;
    private final RoomMemberMapper roomMemberMapper;
    private final NotificationService notificationService;

    @Transactional
    public RoomMember joinPublicRoom(Room room, User user) {
        if (room.getVisibility() != RoomVisibility.PUBLIC) {
            throw new ForbiddenException("Only public rooms can be joined directly");
        }

        if (roomBanRepository.existsByRoomAndUser(room, user)) {
            throw new ForbiddenException("User is banned from this room");
        }

        if (roomMemberRepository.existsByRoomAndUser(room, user)) {
            throw new ConflictException("User is already a member of this room");
        }

        RoomMember member = RoomMember.builder()
                .room(room)
                .user(user)
                .role(RoomRole.MEMBER)
                .build();

        member = roomMemberRepository.save(member);

        // Re-check the ban in-transaction to close the join/ban race (R1-69).
        if (roomBanRepository.existsByRoomAndUser(room, user)) {
            throw new ForbiddenException("User is banned from this room");
        }

        notificationService.ensureMarker(user, room);

        LOG.info("User joined public room: userId={}, roomId={}", user.getId(), room.getId());

        return member;
    }

    @Transactional
    public RoomMember joinPrivateRoomViaInvitation(Room room, User user) {
        RoomInvitation invitation = roomInvitationRepository.findByRoomAndInvitee(room, user)
                .orElseThrow(() -> new ForbiddenException("No invitation found for this user and room"));

        // A banned user cannot rejoin even with an invitation (R1-17).
        if (roomBanRepository.existsByRoomAndUser(room, user)) {
            throw new ForbiddenException("User is banned from this room");
        }

        if (roomMemberRepository.existsByRoomAndUser(room, user)) {
            throw new ConflictException("User is already a member of this room");
        }

        RoomMember member = RoomMember.builder()
                .room(room)
                .user(user)
                .role(RoomRole.MEMBER)
                .build();

        member = roomMemberRepository.save(member);

        // Re-check the ban in-transaction to close the join/ban race (R1-69).
        if (roomBanRepository.existsByRoomAndUser(room, user)) {
            throw new ForbiddenException("User is banned from this room");
        }

        roomInvitationRepository.delete(invitation);
        notificationService.ensureMarker(user, room);

        LOG.info("User joined private room via invitation: userId={}, roomId={}", user.getId(), room.getId());

        return member;
    }

    @Transactional
    public void leaveRoom(Room room, User user) {
        if (room.getVisibility() == RoomVisibility.DIRECT) {
            throw new ForbiddenException("Cannot leave a direct message room");
        }

        RoomMember member = roomMemberRepository.findById(new RoomMemberId(room.getId(), user.getId()))
                .orElseThrow(() -> new ForbiddenException("User is not a member of this room"));

        if (member.getRole() == RoomRole.OWNER) {
            throw new ForbiddenException("Owner cannot leave the room — delete the room instead");
        }

        roomMemberRepository.delete(member);

        LOG.info("User left room: userId={}, roomId={}", user.getId(), room.getId());
    }

    public List<RoomMemberDto> listMembers(Room room) {
        List<RoomMember> members = roomMemberRepository.findByRoomWithUsers(room);
        return roomMemberMapper.toDtoList(members);
    }

    public boolean isMember(Room room, User user) {
        return roomMemberRepository.existsByRoomAndUser(room, user);
    }

    /**
     * Shared read-access guard (design D5): non-members may read only PUBLIC rooms, and a
     * room-banned user loses access entirely (R1-08, R1-56). Mirrors {@code ChatWebController.roomView}.
     */
    public void requireCanRead(Room room, User user) {
        if (roomBanRepository.existsByRoomAndUser(room, user)) {
            throw new ForbiddenException("You are banned from this room");
        }
        if (room.getVisibility() != RoomVisibility.PUBLIC && !isMember(room, user)) {
            throw new ForbiddenException("Access denied to this room");
        }
    }
}
