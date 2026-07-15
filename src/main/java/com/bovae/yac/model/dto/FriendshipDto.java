package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.FriendshipStatus;
import java.time.Instant;
import java.util.UUID;

public record FriendshipDto(
        UUID id,
        UUID requesterId,
        String requesterUsername,
        String requesterDisplayName,
        UUID recipientId,
        String recipientUsername,
        String recipientDisplayName,
        FriendshipStatus status,
        String requestText,
        Instant createdAt) {}
