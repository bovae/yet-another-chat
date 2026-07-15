package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.RoomRole;
import java.time.Instant;
import java.util.UUID;

public record RoomMemberDto(UUID userId, String username, String displayName, RoomRole role, Instant joinedAt) {}
