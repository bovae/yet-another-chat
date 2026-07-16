package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.NotificationEvent;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.NotificationService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/invitations")
@RequiredArgsConstructor
public class RoomInvitationApiController {

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final MessageBroadcastService messageBroadcastService;
    private final NotificationService notificationService;
    private final RoomInvitationRepository roomInvitationRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;

    @PostMapping
    @Transactional
    public ResponseEntity<Void> inviteUser(
            @PathVariable UUID roomId, @Valid @RequestBody InviteRequest request, Principal principal) {
        User inviter = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        User invitee = resolveUserById(request.userId());

        // Only owners/admins of the room may invite (R1-12), matching the UI affordance.
        RoomMember inviterMember = roomMemberRepository
                .findById(new RoomMemberId(room.getId(), inviter.getId()))
                .orElseThrow(() -> new ForbiddenException("Only room members can invite users"));
        if (inviterMember.getRole() != RoomRole.OWNER && inviterMember.getRole() != RoomRole.ADMIN) {
            throw new ForbiddenException("Only owners and admins can invite users");
        }

        roomInvitationRepository.findByRoomAndInvitee(room, invitee).ifPresent(roomInvitationRepository::delete);
        roomInvitationRepository.flush();

        RoomInvitation invitation = RoomInvitation.builder()
                .room(room)
                .inviter(inviter)
                .invitee(invitee)
                .build();

        roomInvitationRepository.save(invitation);

        // Live arrival in the invitee's Room Invitations panel and navbar badge (R5-06),
        // mirroring the friend-request pattern (R2-03).
        notificationService.broadcastNotification(
                invitee, new NotificationEvent("ROOM_INVITATION_CREATED", room.getId(), room.getName(), 0));

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Void> acceptInvitation(
            @PathVariable UUID roomId, @PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        RoomInvitation invitation = roomInvitationRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found: %s".formatted(id)));

        if (!invitation.getRoom().getId().equals(room.getId())) {
            throw new ResourceNotFoundException("Invitation %s does not belong to room %s".formatted(id, roomId));
        }

        if (!invitation.getInvitee().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Invitation %s is not for user %s".formatted(id, user.getId()));
        }

        roomMemberService.joinPrivateRoomViaInvitation(room, user);
        messageBroadcastService.broadcastMembership(room, user, "MEMBER_JOINED");

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> declineOrCancelInvitation(
            @PathVariable UUID roomId, @PathVariable UUID id, Principal principal) {
        User caller = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        RoomInvitation invitation = roomInvitationRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found: %s".formatted(id)));

        if (!invitation.getRoom().getId().equals(room.getId())) {
            throw new ResourceNotFoundException("Invitation %s does not belong to room %s".formatted(id, roomId));
        }

        // Only the invitee, the inviter, or a room admin may decline/cancel (R1-64).
        boolean isInvitee = invitation.getInvitee().getId().equals(caller.getId());
        boolean isInviter = invitation.getInviter().getId().equals(caller.getId());
        boolean isRoomAdmin = roomMemberRepository
                .findById(new RoomMemberId(room.getId(), caller.getId()))
                .map(m -> m.getRole() == RoomRole.OWNER || m.getRole() == RoomRole.ADMIN)
                .orElse(false);
        if (!isInvitee && !isInviter && !isRoomAdmin) {
            throw new ForbiddenException("Not allowed to modify this invitation");
        }

        roomInvitationRepository.delete(invitation);

        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Principal principal) {
        return userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }

    private User resolveUserById(UUID userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: %s".formatted(userId)));
    }

    public record InviteRequest(@NotNull UUID userId) {}
}
