package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/invitations")
@RequiredArgsConstructor
public class RoomInvitationApiController {

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final RoomInvitationRepository roomInvitationRepository;
    private final UserRepository userRepository;

    @PostMapping
    @Transactional
    public ResponseEntity<Void> inviteUser(
            @PathVariable UUID roomId,
            @Valid @RequestBody InviteRequest request,
            Principal principal) {
        User inviter = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        User invitee = resolveUserById(request.userId());

        roomInvitationRepository.findByRoomAndInvitee(room, invitee)
                .ifPresent(roomInvitationRepository::delete);
        roomInvitationRepository.flush();

        RoomInvitation invitation = RoomInvitation.builder()
                .room(room)
                .inviter(inviter)
                .invitee(invitee)
                .build();

        roomInvitationRepository.save(invitation);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Void> acceptInvitation(
            @PathVariable UUID roomId,
            @PathVariable UUID id,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        RoomInvitation invitation = roomInvitationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invitation not found: %s".formatted(id)));

        if (!invitation.getRoom().getId().equals(room.getId())) {
            throw new ResourceNotFoundException(
                    "Invitation %s does not belong to room %s".formatted(id, roomId));
        }

        if (!invitation.getInvitee().getId().equals(user.getId())) {
            throw new ResourceNotFoundException(
                    "Invitation %s is not for user %s".formatted(id, user.getId()));
        }

        roomMemberService.joinPrivateRoomViaInvitation(room, user);

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> declineOrCancelInvitation(
            @PathVariable UUID roomId,
            @PathVariable UUID id,
            Principal principal) {
        resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        RoomInvitation invitation = roomInvitationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invitation not found: %s".formatted(id)));

        if (!invitation.getRoom().getId().equals(room.getId())) {
            throw new ResourceNotFoundException(
                    "Invitation %s does not belong to room %s".formatted(id, roomId));
        }

        roomInvitationRepository.delete(invitation);

        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }

    private User resolveUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: %s".formatted(userId)));
    }

    public record InviteRequest(
            @NotNull UUID userId
    ) {}
}
