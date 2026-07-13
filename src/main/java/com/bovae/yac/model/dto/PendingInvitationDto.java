package com.bovae.yac.model.dto;

import java.time.Instant;
import java.util.UUID;

public record PendingInvitationDto(
    UUID invitationId,
    UUID roomId,
    String roomName,
    String inviterUsername,
    Instant createdAt
) {}
