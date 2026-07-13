package com.bovae.yac.model.dto;

import java.time.Instant;
import java.util.UUID;

public record UserDto(
        UUID id,
        String email,
        String username,
        String displayName,
        Instant createdAt
) {}
