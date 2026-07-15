package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.PresenceStatus;
import java.time.Instant;
import java.util.UUID;

public record PresenceUpdate(UUID userId, String username, PresenceStatus status, Instant timestamp) {}
