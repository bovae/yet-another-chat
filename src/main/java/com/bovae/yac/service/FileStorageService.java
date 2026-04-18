package com.bovae.yac.service;

import com.bovae.yac.config.properties.FileStorageProperties;
import com.bovae.yac.exception.FileStorageException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileStorageProperties properties;
    private Path storageLocation;

    @PostConstruct
    public void init() {
        storageLocation = Paths.get(properties.basePath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageLocation);
            LOG.info("File storage initialized at: {}", storageLocation);
        } catch (IOException ex) {
            throw new FileStorageException("Could not create file storage directory", ex);
        }
    }

    public Path saveFile(MultipartFile file, String targetPath) {
        // Skeleton — actual implementation during feature development
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public Path resolveFilePath(String storagePath) {
        Path resolved = storageLocation.resolve(storagePath).normalize();
        if (!resolved.startsWith(storageLocation)) {
            throw new FileStorageException("Invalid storage path: path traversal detected");
        }
        return resolved;
    }

    public void deleteFile(String storagePath) {
        // Skeleton — actual implementation during feature development
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public void validateFileSize(long fileSize, boolean isImage) {
        long maxSize = isImage ? properties.maxImageSize().toBytes() : properties.maxFileSize().toBytes();
        if (fileSize > maxSize) {
            throw new FileStorageException("File size exceeds maximum allowed size of %d bytes".formatted(maxSize));
        }
    }
}
