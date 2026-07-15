package com.bovae.yac.model.dto;

import jakarta.validation.constraints.NotBlank;

public record SendFriendRequest(@NotBlank String username, String requestText) {}
