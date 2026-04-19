package com.bovae.yac.controller.api;

import com.bovae.yac.model.dto.PresenceStatusEntry;
import com.bovae.yac.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/presence")
@RequiredArgsConstructor
public class PresenceApiController {

    private final PresenceService presenceService;

    @GetMapping
    public ResponseEntity<List<PresenceStatusEntry>> getPresence(
            @RequestParam String userIds) {
        List<UUID> ids = Arrays.stream(userIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();
        List<PresenceStatusEntry> result = ids.stream()
                .map(id -> new PresenceStatusEntry(id, presenceService.getUserStatus(id)))
                .toList();
        return ResponseEntity.ok(result);
    }
}
