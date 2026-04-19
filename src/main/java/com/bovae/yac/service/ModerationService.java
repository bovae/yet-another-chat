package com.bovae.yac.service;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModerationService {

    private final RoomMemberRepository roomMemberRepository;
    private final RoomBanRepository roomBanRepository;
    private final MessageRepository messageRepository;

    @Transactional
    public void kickMember(Room room, User actingUser, User targetUser) {
        requireAdminPrivileges(room, actingUser);
        RoomMember target = getMember(room, targetUser);

        if (target.getRole() == RoomRole.OWNER) {
            throw new ForbiddenException("Cannot kick the room owner");
        }

        RoomBan ban = RoomBan.builder()
                .room(room)
                .user(targetUser)
                .bannedBy(actingUser)
                .build();
        roomBanRepository.save(ban);
        roomMemberRepository.delete(target);

        LOG.info("Member kicked: actorId={}, targetId={}, roomId={}",
                actingUser.getId(), targetUser.getId(), room.getId());
    }

    @Transactional
    public void banUserFromRoom(Room room, User actingUser, User targetUser) {
        requireAdminPrivileges(room, actingUser);

        if (roomBanRepository.existsByRoomAndUser(room, targetUser)) {
            LOG.debug("User already banned from room: userId={}, roomId={}", targetUser.getId(), room.getId());
            return;
        }

        RoomBan ban = RoomBan.builder()
                .room(room)
                .user(targetUser)
                .bannedBy(actingUser)
                .build();
        roomBanRepository.save(ban);

        LOG.info("User banned from room: actorId={}, targetId={}, roomId={}",
                actingUser.getId(), targetUser.getId(), room.getId());
    }

    @Transactional
    public void unbanUserFromRoom(Room room, User actingUser, User targetUser) {
        requireAdminPrivileges(room, actingUser);

        RoomBan ban = roomBanRepository.findByRoomAndUser(room, targetUser)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ban found for userId=%s in roomId=%s".formatted(targetUser.getId(), room.getId())));

        roomBanRepository.delete(ban);

        LOG.info("User unbanned from room: actorId={}, targetId={}, roomId={}",
                actingUser.getId(), targetUser.getId(), room.getId());
    }

    @Transactional
    public void deleteMessage(Room room, User actingUser, UUID messageId) {
        requireAdminPrivileges(room, actingUser);

        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message not found: messageId=%s".formatted(messageId)));

        if (!message.getRoom().getId().equals(room.getId())) {
            throw new ForbiddenException("Message does not belong to this room");
        }

        messageRepository.delete(message);

        LOG.info("Message deleted by admin: actorId={}, messageId={}, roomId={}",
                actingUser.getId(), messageId, room.getId());
    }

    @Transactional
    public void grantAdminRole(Room room, User actingUser, User targetUser) {
        RoomMember actor = getMember(room, actingUser);

        if (actor.getRole() != RoomRole.OWNER) {
            throw new ForbiddenException("Only the room owner can grant admin role");
        }

        RoomMember target = getMember(room, targetUser);

        target.setRole(RoomRole.ADMIN);
        roomMemberRepository.save(target);

        LOG.info("Admin role granted: ownerId={}, targetId={}, roomId={}",
                actingUser.getId(), targetUser.getId(), room.getId());
    }

    @Transactional
    public void revokeAdminRole(Room room, User actingUser, User targetUser) {
        RoomMember actor = getMember(room, actingUser);
        RoomMember target = getMember(room, targetUser);

        if (target.getRole() == RoomRole.OWNER) {
            throw new ForbiddenException("Cannot demote the room owner");
        }

        if (actor.getRole() == RoomRole.OWNER) {
            target.setRole(RoomRole.MEMBER);
            roomMemberRepository.save(target);
        } else if (actor.getRole() == RoomRole.ADMIN) {
            if (target.getRole() != RoomRole.ADMIN) {
                throw new ForbiddenException("Admin can only demote another admin");
            }
            target.setRole(RoomRole.MEMBER);
            roomMemberRepository.save(target);
        } else {
            throw new ForbiddenException("Only admins and owners can revoke admin role");
        }

        LOG.info("Admin role revoked: actorId={}, targetId={}, roomId={}",
                actingUser.getId(), targetUser.getId(), room.getId());
    }

    private RoomMember getActorWithAdminPrivileges(Room room, User actingUser) {
        RoomMember actor = getMember(room, actingUser);

        if (actor.getRole() != RoomRole.ADMIN && actor.getRole() != RoomRole.OWNER) {
            throw new ForbiddenException("Only admins and owners can perform moderation actions");
        }

        return actor;
    }

    private void requireAdminPrivileges(Room room, User actingUser) {
        getActorWithAdminPrivileges(room, actingUser);
    }

    private RoomMember getMember(Room room, User user) {
        return roomMemberRepository.findById(new RoomMemberId(room.getId(), user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User is not a member of this room: userId=%s, roomId=%s".formatted(user.getId(), room.getId())));
    }
}
