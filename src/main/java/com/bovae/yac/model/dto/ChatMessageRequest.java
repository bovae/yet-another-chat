package com.bovae.yac.model.dto;

import com.bovae.yac.validation.MaxByteSize;
import com.bovae.yac.validation.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatMessageRequest(
        @NotBlank @MaxByteSize(3072) String content,
        @NotNull @UUID String roomId
) {
}
