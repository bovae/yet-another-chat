package com.bovae.yac.model.dto;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String message,
        @Nullable String path) {}
