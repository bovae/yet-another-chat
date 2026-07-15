package com.bovae.yac.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MessagePageRequest(
        @NotNull UUID roomId, Long cursor, @Max(100) int size) {}
