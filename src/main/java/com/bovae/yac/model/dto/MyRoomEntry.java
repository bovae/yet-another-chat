package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.RoomVisibility;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MyRoomEntry(
        UUID id,
        String name,
        RoomVisibility visibility,
        int unreadCount,
        @Nullable String otherUsername,
        @Nullable String otherDisplayName) {}
