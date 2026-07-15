package com.bovae.yac.model.dto;

import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MessageEditedEvent(String type, UUID messageId, UUID roomId, String content, boolean edited) {

    public static MessageEditedEvent of(UUID messageId, UUID roomId, String content) {
        return new MessageEditedEvent("MESSAGE_EDITED", messageId, roomId, content, true);
    }
}
