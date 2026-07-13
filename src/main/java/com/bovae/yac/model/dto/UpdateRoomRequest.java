package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.RoomVisibility;

public record UpdateRoomRequest(
        String name,
        String description,
        RoomVisibility visibility
) {
}
