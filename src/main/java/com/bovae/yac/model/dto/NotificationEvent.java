package com.bovae.yac.model.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record NotificationEvent(
        String type, @Nullable UUID roomId, @Nullable String roomName, int unreadCount) {}
