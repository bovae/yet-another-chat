package com.bovae.yac.service;

import com.bovae.yac.config.properties.FileStorageProperties;
import com.bovae.yac.exception.FileStorageException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_IMAGE_SIZE_BYTES = 3L * 1024 * 1024;
    private static final String IMAGE_CONTENT_TYPE_PREFIX = "image/";

    private final AttachmentRepository attachmentRepository;
    private final RoomMemberService roomMemberService;
    private final FileStorageProperties fileStorageProperties;

    @Transactional
    public Attachment uploadFile(MultipartFile file, Message message, Room room, User uploader, String comment) {
        if (!roomMemberService.isMember(room, uploader)) {
            throw new ForbiddenException("User is not a member of this room");
        }

        validateFileSize(file);

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.isBlank()) {
            originalFileName = "unnamed";
        }

        String storedFileName = UUID.randomUUID() + "_" + originalFileName;
        Path roomDir = Paths.get(fileStorageProperties.basePath(), room.getId().toString());
        Path targetPath = roomDir.resolve(storedFileName);

        try {
            Files.createDirectories(roomDir);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FileStorageException("Failed to store file: %s".formatted(originalFileName), e);
        }

        Attachment attachment = Attachment.builder()
                .message(message)
                .originalFileName(originalFileName)
                .storagePath(targetPath.toString())
                .fileSize(file.getSize())
                .contentType(file.getContentType())
                .comment(comment)
                .build();

        attachment = attachmentRepository.save(attachment);

        LOG.info("File uploaded: attachmentId={}, roomId={}, fileName={}, size={}",
                attachment.getId(), room.getId(), originalFileName, file.getSize());

        return attachment;
    }

    @Transactional(readOnly = true)
    public Resource downloadFile(UUID attachmentId, Room room, User requester) {
        if (!roomMemberService.isMember(room, requester)) {
            throw new ForbiddenException("User is not a member of this room");
        }

        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attachment not found: %s".formatted(attachmentId)));

        Path filePath = Paths.get(attachment.getStoragePath());
        if (!Files.exists(filePath)) {
            throw new FileStorageException("File not found on disk: %s".formatted(attachment.getOriginalFileName()));
        }

        LOG.info("File downloaded: attachmentId={}, roomId={}, requesterId={}",
                attachmentId, room.getId(), requester.getId());

        try {
            return new UrlResource(filePath.toUri());
        } catch (Exception e) {
            throw new FileStorageException("Failed to read file: %s".formatted(attachment.getOriginalFileName()), e);
        }
    }

    private void validateFileSize(MultipartFile file) {
        String contentType = file.getContentType();
        long size = file.getSize();

        if (contentType != null && contentType.startsWith(IMAGE_CONTENT_TYPE_PREFIX)) {
            if (size > MAX_IMAGE_SIZE_BYTES) {
                throw new FileStorageException(
                        "Image size exceeds maximum of 3 MB (was %d bytes)".formatted(size));
            }
        } else {
            if (size > MAX_FILE_SIZE_BYTES) {
                throw new FileStorageException(
                        "File size exceeds maximum of 20 MB (was %d bytes)".formatted(size));
            }
        }
    }
}
