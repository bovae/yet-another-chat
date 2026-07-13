package com.bovae.yac.unit;

import com.bovae.yac.config.properties.FileStorageProperties;
import com.bovae.yac.exception.FileStorageException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FileStorageService}. Membership is enforced by the controller now; this
 * layer verifies the message→room→author chain (R1-32), content-type sniffing size caps (R1-34),
 * and download room-scoping (R1-15).
 */
@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    // 8-byte PNG signature so URLConnection.guessContentTypeFromStream reports image/png.
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PLAIN_BYTES = "just some file bytes".getBytes();

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private FileStorageProperties fileStorageProperties;

    @InjectMocks
    private FileStorageService fileStorageService;

    private User uploader;
    private Room room;
    private Message message;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        uploader = User.builder()
                .id(UUID.randomUUID()).email("alice@test.com").username("alice").passwordHash("$2a$10$hash").build();

        room = Room.builder()
                .id(UUID.randomUUID()).name("test-room").visibility(RoomVisibility.PUBLIC)
                .owner(uploader).nextWatermark(1L).build();

        message = Message.builder()
                .id(UUID.randomUUID()).room(room).sender(uploader).content("hello").watermark(1L).build();
    }

    private MultipartFile fileOf(String name, long size, byte[] bytes) throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        lenient().when(f.getOriginalFilename()).thenReturn(name);
        lenient().when(f.getSize()).thenReturn(size);
        lenient().when(f.getInputStream()).thenAnswer(inv -> new ByteArrayInputStream(bytes));
        return f;
    }

    @Test
    void uploadFile_storesFileAndSniffsContentType() throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        MultipartFile file = fileOf("report.pdf", 1024L, PLAIN_BYTES);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        fileStorageService.uploadFile(file, message, room, uploader, "my comment");

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepository).save(captor.capture());
        Attachment saved = captor.getValue();
        assertThat(saved.getOriginalFileName()).isEqualTo("report.pdf");
        assertThat(saved.getComment()).isEqualTo("my comment");
        // Client content type is ignored; unknown bytes sniff to octet-stream (R1-34).
        assertThat(saved.getContentType()).isEqualTo("application/octet-stream");

        Path roomDir = tempDir.resolve(room.getId().toString());
        assertThat(Files.exists(roomDir)).isTrue();
    }

    @Test
    void uploadFile_sniffedImageOverCap_rejected() throws IOException {
        MultipartFile file = fileOf("photo.png", 3L * 1024 * 1024 + 1, PNG_MAGIC);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, message, room, uploader, null))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("3 MB");

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void uploadFile_nonImageOver20MB_rejected() throws IOException {
        MultipartFile file = fileOf("big.bin", 20L * 1024 * 1024 + 1, PLAIN_BYTES);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, message, room, uploader, null))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("20 MB");

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void uploadFile_imageExactly3MB_succeeds() throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        MultipartFile file = fileOf("photo.png", 3L * 1024 * 1024, PNG_MAGIC);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        fileStorageService.uploadFile(file, message, room, uploader, null);

        verify(attachmentRepository).save(any(Attachment.class));
    }

    @Test
    void uploadFile_messageNotInPathRoom_throwsNotFound() throws IOException {
        Room otherRoom = Room.builder().id(UUID.randomUUID()).name("other").visibility(RoomVisibility.PUBLIC)
                .owner(uploader).nextWatermark(1L).build();
        MultipartFile file = fileOf("f.txt", 10L, PLAIN_BYTES);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, message, otherRoom, uploader, null))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void uploadFile_notAuthor_throwsForbidden() throws IOException {
        User other = User.builder().id(UUID.randomUUID()).email("bob@test.com").username("bob")
                .passwordHash("$2a$10$hash").build();
        MultipartFile file = fileOf("f.txt", 10L, PLAIN_BYTES);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, message, room, other, null))
                .isInstanceOf(ForbiddenException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void downloadFile_attachmentInRoom_succeeds() throws IOException {
        Path roomDir = tempDir.resolve(room.getId().toString());
        Files.createDirectories(roomDir);
        Path filePath = roomDir.resolve("stored-file.pdf");
        Files.writeString(filePath, "file-content");

        UUID attachmentId = UUID.randomUUID();
        Attachment attachment = Attachment.builder()
                .id(attachmentId).message(message).originalFileName("report.pdf")
                .storagePath(filePath.toString()).fileSize(12L).contentType("application/octet-stream").build();
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));

        Resource result = fileStorageService.downloadFile(attachmentId, room, uploader);

        assertThat(result).isNotNull();
        assertThat(result.exists()).isTrue();
    }

    @Test
    void downloadFile_attachmentInDifferentRoom_throwsNotFound() {
        Room otherRoom = Room.builder().id(UUID.randomUUID()).name("other").visibility(RoomVisibility.PUBLIC)
                .owner(uploader).nextWatermark(1L).build();
        UUID attachmentId = UUID.randomUUID();
        // Attachment's message belongs to `room`, but the request names `otherRoom` (R1-15).
        Attachment attachment = Attachment.builder()
                .id(attachmentId).message(message).originalFileName("report.pdf")
                .storagePath("/x").fileSize(1L).build();
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> fileStorageService.downloadFile(attachmentId, otherRoom, uploader))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void downloadFile_nonExistentAttachment_throwsNotFound() {
        UUID attachmentId = UUID.randomUUID();
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileStorageService.downloadFile(attachmentId, room, uploader))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Attachment not found");
    }
}
