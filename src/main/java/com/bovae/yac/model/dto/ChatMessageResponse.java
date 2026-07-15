package com.bovae.yac.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ChatMessageResponse(
        UUID id,
        UUID roomId,
        @Nullable UUID senderId,
        String senderUsername,
        @Nullable String senderDisplayName,
        String content,
        @Nullable UUID replyToId,
        @Nullable String replyToSenderUsername,
        @Nullable String replyToContentSnippet,
        boolean edited,
        Long watermark,
        Instant createdAt,
        List<AttachmentInfo> attachments) {}
