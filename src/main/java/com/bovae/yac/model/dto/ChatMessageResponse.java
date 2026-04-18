package com.bovae.yac.model.dto;

import java.time.Instant;

public record ChatMessageResponse(
        String sender,
        String content,
        String roomId,
        Instant timestamp
) {
}
