package com.bovae.yac.model.dto;

import com.bovae.yac.validation.MaxByteSize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChatMessageRequest(
        @NotNull UUID roomId, @NotBlank @MaxByteSize(3072) String content, UUID replyToId) {}
