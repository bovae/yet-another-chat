package com.bovae.yac.model.dto;

import java.time.Instant;
import java.util.UUID;

public record DirectChatDto(
        UUID id, String name, UUID otherUserId, String otherUsername, String otherDisplayName, Instant createdAt) {}
