package com.bovae.yac.model.dto;

import com.bovae.yac.validation.MaxByteSize;
import jakarta.validation.constraints.NotBlank;

public record EditMessageRequest(
        @NotBlank @MaxByteSize(3072) String content
) {
}
