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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_IMAGE_SIZE_BYTES = 3L * 1024 * 1024;
    private static final String IMAGE_CONTENT_TYPE_PREFIX = "image/";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final AttachmentRepository attachmentRepository;
    private final FileStorageProperties fileStorageProperties;

    @Transactional
    public Attachment uploadFile(MultipartFile file, Message message, Room room, User uploader, String comment) {
        // The message must belong to the path room and be authored by the uploader (R1-32).
        if (!message.getRoom().getId().equals(room.getId())) {
            throw new ResourceNotFoundException("Message not found in this room");
        }
        if (message.getSender() == null || !message.getSender().getId().equals(uploader.getId())) {
            throw new ForbiddenException("You can only attach files to your own messages");
        }

        // Content type is sniffed from the bytes; the client-declared type is ignored (R1-34).
        String sniffedType = sniffContentType(file);
        validateFileSize(file, sniffedType);

        // Reduce the filename to its last segment so traversal sequences cannot escape (R1-14).
        String safeName = sanitizeFileName(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + "_" + safeName;

        Path roomDir = Paths.get(fileStorageProperties.basePath(), room.getId().toString())
                .toAbsolutePath().normalize();
        Path targetPath = roomDir.resolve(storedFileName).normalize();
        if (!targetPath.startsWith(roomDir)) {
            throw new FileStorageException("Resolved path escapes the room directory");
        }

        try {
            Files.createDirectories(roomDir);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FileStorageException("Failed to store file: %s".formatted(safeName), e);
        }

        // If the surrounding transaction rolls back after this copy, remove the orphan file (R1-71).
        registerRollbackCleanup(targetPath);

        Attachment attachment = Attachment.builder()
                .message(message)
                .originalFileName(safeName)
                .storagePath(targetPath.toString())
                .fileSize(file.getSize())
                .contentType(sniffedType != null ? sniffedType : DEFAULT_CONTENT_TYPE)
                .comment(comment)
                .build();

        attachment = attachmentRepository.save(attachment);

        LOG.info("File uploaded: attachmentId={}, roomId={}, fileName={}, size={}",
                attachment.getId(), room.getId(), safeName, file.getSize());

        return attachment;
    }

    @Transactional(readOnly = true)
    public Resource downloadFile(UUID attachmentId, Room room, User requester) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attachment not found: %s".formatted(attachmentId)));

        // The attachment's message must belong to the room in the URL (R1-15). Membership was
        // already checked by the caller.
        if (!attachment.getMessage().getRoom().getId().equals(room.getId())) {
            throw new ResourceNotFoundException("Attachment not found in this room: %s".formatted(attachmentId));
        }

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

    /** Deletes individual attachment files after the surrounding transaction commits (R1-16). */
    public void deleteFilesAfterCommit(Collection<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        registerAfterCommit(() -> files.forEach(this::deleteFileQuietly));
    }

    /** Deletes a room's storage directory recursively after commit (R1-16). */
    public void deleteRoomDirectoryAfterCommit(UUID roomId) {
        Path roomDir = Paths.get(fileStorageProperties.basePath(), roomId.toString());
        registerAfterCommit(() -> deleteDirectoryQuietly(roomDir));
    }

    private String sniffContentType(MultipartFile file) {
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            return URLConnection.guessContentTypeFromStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    private void validateFileSize(MultipartFile file, String sniffedType) {
        long size = file.getSize();
        boolean isImage = sniffedType != null && sniffedType.startsWith(IMAGE_CONTENT_TYPE_PREFIX);
        if (isImage) {
            if (size > MAX_IMAGE_SIZE_BYTES) {
                throw new FileStorageException("Image size exceeds maximum of 3 MB (was %d bytes)".formatted(size));
            }
        } else if (size > MAX_FILE_SIZE_BYTES) {
            throw new FileStorageException("File size exceeds maximum of 20 MB (was %d bytes)".formatted(size));
        }
    }

    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "unnamed";
        }
        Path name = Paths.get(original).getFileName();
        if (name == null) {
            return "unnamed";
        }
        String segment = name.toString();
        return segment.isBlank() ? "unnamed" : segment;
    }

    private void registerRollbackCleanup(Path file) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteFileQuietly(file);
                }
            }
        });
    }

    private void registerAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void deleteFileQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Failed to delete file {}: {}", file, e.getMessage());
        }
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteFileQuietly);
        } catch (IOException e) {
            LOG.warn("Failed to delete directory {}: {}", dir, e.getMessage());
        }
    }
}
