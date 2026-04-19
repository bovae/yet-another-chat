package com.bovae.yac.model.dto;

import java.util.UUID;

public record NotificationEvent(
        String type,
        UUID roomId,
        String roomName,
        int unreadCount
) {
}
