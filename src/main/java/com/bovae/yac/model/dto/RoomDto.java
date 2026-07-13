package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.RoomVisibility;

import java.time.Instant;
import java.util.UUID;

public record RoomDto(
        UUID id,
        String name,
        String description,
        RoomVisibility visibility,
        UUID ownerId,
        String ownerUsername,
        Long nextWatermark,
        Instant createdAt
) {}
