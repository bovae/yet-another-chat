package com.bovae.yac.model.dto;

import com.bovae.yac.model.enums.PresenceStatus;

import java.util.UUID;

public record PresenceStatusEntry(UUID userId, PresenceStatus status) {}
