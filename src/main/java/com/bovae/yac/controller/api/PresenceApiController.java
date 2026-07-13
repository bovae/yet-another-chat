package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.PresenceStatusEntry;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import com.bovae.yac.service.PresenceVisibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/presence")
@RequiredArgsConstructor
public class PresenceApiController {

    private static final int MAX_IDS = 100;

    private final PresenceService presenceService;
    private final PresenceVisibilityService presenceVisibilityService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<PresenceStatusEntry>> getPresence(
            @RequestParam String userIds,
            Principal principal) {
        User caller = resolveUser(principal);

        List<String> raw = Arrays.stream(userIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (raw.size() > MAX_IDS) {
            // R1-67: bound the number of ids so a client can't request the whole user base.
            throw new IllegalArgumentException("Too many userIds requested (max %d)".formatted(MAX_IDS));
        }

        List<UUID> ids;
        try {
            ids = raw.stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("userIds contains a malformed UUID");
        }

        // R1-65: only friends, co-members, and self are visible.
        Set<UUID> visible = presenceVisibilityService.visibleAmong(caller.getId(), ids);
        List<PresenceStatusEntry> result = ids.stream()
                .filter(visible::contains)
                .map(id -> new PresenceStatusEntry(id, presenceService.getUserStatus(id)))
                .toList();
        return ResponseEntity.ok(result);
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
