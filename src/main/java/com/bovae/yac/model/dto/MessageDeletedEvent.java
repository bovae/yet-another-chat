package com.bovae.yac.model.dto;

import java.util.UUID;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MessageDeletedEvent(
        String type,
        UUID messageId,
        UUID roomId,
        UUID deletedBy
) {

    public static MessageDeletedEvent of(UUID messageId, UUID roomId, UUID deletedBy) {
        return new MessageDeletedEvent("MESSAGE_DELETED", messageId, roomId, deletedBy);
    }
}
