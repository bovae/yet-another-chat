package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.RoomVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank @Size(max = 100) String name,
        String description,
        @NotNull RoomVisibility visibility
) {
}
