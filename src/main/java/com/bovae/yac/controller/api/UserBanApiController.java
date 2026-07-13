package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.UserBanDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.UserBanService;
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
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/user-bans")
@RequiredArgsConstructor
public class UserBanApiController {

    private final UserBanService userBanService;
    private final UserBanRepository userBanRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<UserBanDto>> listBans(Principal principal) {
        User user = resolveUser(principal);
        return ResponseEntity.ok(userBanService.listBannedUsers(user));
    }

    @PostMapping
    public ResponseEntity<Void> banUser(
            @Valid @RequestBody BanUserRequest request,
            Principal principal) {
        User blocker = resolveUser(principal);
        User blocked = resolveUserById(request.userId());

        userBanService.banUser(blocker, blocked);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unbanUser(
            @PathVariable UUID id,
            Principal principal) {
        User blocker = resolveUser(principal);

        UserBan userBan = userBanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "UserBan not found: %s".formatted(id)));

        userBanService.unbanUser(blocker, userBan.getBlocked());

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

    public record BanUserRequest(
            @NotNull UUID userId
    ) {}
}
