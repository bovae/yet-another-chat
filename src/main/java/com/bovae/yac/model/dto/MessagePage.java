package com.bovae.yac.model.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record MessagePage(
        List<ChatMessageResponse> messages, @Nullable Long nextCursor, boolean hasMore) {}
