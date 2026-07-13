package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.RoomVisibility;

import java.util.UUID;

public record MyRoomEntry(
        UUID id,
        String name,
        RoomVisibility visibility,
        int unreadCount,
        String otherUsername,
        String otherDisplayName
) {
}
