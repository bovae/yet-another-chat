package com.bovae.yac.model.dto;

import java.util.UUID;

public record RoomEvent(String type, UUID roomId, UUID userId, String username) {}
