package com.bovae.yac.model.dto;

import java.time.Instant;

public record HealthResponse(String status, Instant timestamp) {}
