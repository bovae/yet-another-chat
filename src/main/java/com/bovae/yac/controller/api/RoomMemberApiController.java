package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/members")
@RequiredArgsConstructor
public class RoomMemberApiController {

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final ModerationService moderationService;
    private final MessageBroadcastService messageBroadcastService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<RoomMemberDto>> listMembers(@PathVariable UUID roomId, Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        roomMemberService.requireCanRead(room, user); // R1-56

        List<RoomMemberDto> members = roomMemberService.listMembers(room);

        return ResponseEntity.ok(members);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> kickMember(@PathVariable UUID roomId, @PathVariable UUID userId, Principal principal) {
        User actingUser = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        User targetUser = resolveUserById(userId);

        moderationService.kickMember(room, actingUser, targetUser);
        messageBroadcastService.broadcastMembership(room, targetUser, "MEMBER_BANNED");

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/role")
    public ResponseEntity<Void> changeRole(
            @PathVariable UUID roomId,
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeRoleRequest request,
            Principal principal) {
        User actingUser = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        User targetUser = resolveUserById(userId);

        if (request.role() == RoomRole.ADMIN) {
            moderationService.grantAdminRole(room, actingUser, targetUser);
        } else {
            moderationService.revokeAdminRole(room, actingUser, targetUser);
        }

        return ResponseEntity.ok().build();
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

    public record ChangeRoleRequest(@NotNull RoomRole role) {}
}
