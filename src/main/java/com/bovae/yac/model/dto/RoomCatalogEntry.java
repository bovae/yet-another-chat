package com.bovae.yac.model.dto;

import java.util.UUID;

public record RoomCatalogEntry(UUID id, String name, String description, int memberCount) {}
