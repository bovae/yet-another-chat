package com.bovae.yac.model.dto;

import java.util.UUID;

public record AttachmentInfo(UUID id, String originalFileName, String contentType, long fileSize, String comment) {}
