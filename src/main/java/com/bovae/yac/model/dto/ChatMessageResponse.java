package com.bovae.yac.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatMessageResponse(
        UUID id,
        UUID roomId,
        UUID senderId,
        String senderUsername,
        String content,
        UUID replyToId,
        boolean edited,
        Long watermark,
        Instant createdAt,
        List<AttachmentInfo> attachments
) {
}
