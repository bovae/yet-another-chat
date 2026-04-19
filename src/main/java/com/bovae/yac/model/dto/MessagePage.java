package com.bovae.yac.model.dto;

import java.util.List;

public record MessagePage(
        List<ChatMessageResponse> messages,
        Long nextCursor,
        boolean hasMore
) {
}
