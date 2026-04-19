package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.ModerationService;
import com.bovae.yac.service.RoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/bans")
@RequiredArgsConstructor
public class RoomBanApiController {

    private final RoomService roomService;
    private final ModerationService moderationService;
    private final RoomBanRepository roomBanRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<BanResponse>> listBans(
            @PathVariable UUID roomId,
            Principal principal) {
        resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        List<BanResponse> bans = roomBanRepository.findByRoom(room).stream()
                .map(this::toBanResponse)
                .toList();

        return ResponseEntity.ok(bans);
    }

    @PostMapping
    public ResponseEntity<Void> banUser(
            @PathVariable UUID roomId,
            @Valid @RequestBody BanRequest request,
            Principal principal) {
        User actingUser = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        User targetUser = resolveUserById(request.userId());

        moderationService.banUserFromRoom(room, actingUser, targetUser);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> unbanUser(
            @PathVariable UUID roomId,
            @PathVariable UUID userId,
            Principal principal) {
        User actingUser = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        User targetUser = resolveUserById(userId);

        moderationService.unbanUserFromRoom(room, actingUser, targetUser);

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

    private BanResponse toBanResponse(RoomBan ban) {
        return new BanResponse(
                ban.getId(),
                ban.getUser().getId(),
                ban.getUser().getUsername(),
                ban.getBannedBy().getId(),
                ban.getBannedBy().getUsername(),
                ban.getCreatedAt()
        );
    }

    public record BanResponse(
            UUID id,
            UUID userId,
            String username,
            UUID bannedById,
            String bannedByUsername,
            Instant createdAt
    ) {}

    public record BanRequest(
            @NotNull UUID userId
    ) {}
}
