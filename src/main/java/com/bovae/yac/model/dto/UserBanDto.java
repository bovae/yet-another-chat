package com.bovae.yac.model.dto;

import java.time.Instant;
import java.util.UUID;

public record UserBanDto(
        UUID id,
        UUID blockedId,
        String blockedUsername,
        String blockedDisplayName,
        Instant createdAt
) {}
