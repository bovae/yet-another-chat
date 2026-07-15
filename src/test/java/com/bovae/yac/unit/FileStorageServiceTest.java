package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

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
    // "<!" prefix makes URLConnection.guessContentTypeFromStream report text/html (non-image).
    private static final byte[] HTML_BYTES = "<!DOCTYPE html><html></html>".getBytes();

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
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("$2a$10$hash")
                .build();

        room = Room.builder()
                .id(UUID.randomUUID())
                .name("test-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(uploader)
                .nextWatermark(1L)
                .build();

        message = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(uploader)
                .content("hello")
                .watermark(1L)
                .build();
    }

    private MultipartFile fileOf(String name, long size, byte[] bytes) throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        lenient().when(f.getOriginalFilename()).thenReturn(name);
        lenient().when(f.getSize()).thenReturn(size);
        lenient().when(f.getInputStream()).thenAnswer(inv -> new ByteArrayInputStream(bytes));
        return f;
    }

    // --- upload ---

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
    void uploadFile_nonImageExactly20MB_succeeds() throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        // Exactly at the non-image cap: the guard rejects strictly above 20 MB, so this must pass.
        MultipartFile file = fileOf("big.bin", 20L * 1024 * 1024, PLAIN_BYTES);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        fileStorageService.uploadFile(file, message, room, uploader, null);

        verify(attachmentRepository).save(any(Attachment.class));
    }

    @Test
    void uploadFile_messageNotInPathRoom_throwsNotFound() throws IOException {
        Room otherRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("other")
                .visibility(RoomVisibility.PUBLIC)
                .owner(uploader)
                .nextWatermark(1L)
                .build();
        MultipartFile file = fileOf("f.txt", 10L, PLAIN_BYTES);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, message, otherRoom, uploader, null))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void uploadFile_notAuthor_throwsForbidden() throws IOException {
        User other = User.builder()
                .id(UUID.randomUUID())
                .email("bob@test.com")
                .username("bob")
                .passwordHash("$2a$10$hash")
                .build();
        MultipartFile file = fileOf("f.txt", 10L, PLAIN_BYTES);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, message, room, other, null))
                .isInstanceOf(ForbiddenException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void uploadFile_shouldThrowForbidden_whenMessageHasNoSender() throws IOException {
        Message noSender = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(null)
                .content("hi")
                .watermark(2L)
                .build();
        MultipartFile file = fileOf("f.txt", 10L, PLAIN_BYTES);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, noSender, room, uploader, null))
                .isInstanceOf(ForbiddenException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void uploadFile_shouldStoreWithSniffedType_whenSniffedTypeIsNonImage() throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        MultipartFile file = fileOf("page.html", 1024L, HTML_BYTES);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        Attachment saved = fileStorageService.uploadFile(file, message, room, uploader, null);

        // Non-null, non-image sniffed type takes the else-branch of the size cap and is stored verbatim.
        assertThat(saved.getContentType()).isEqualTo("text/html");
    }

    @ParameterizedTest(name = "originalFilename=[{0}] -> stored as \"unnamed\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "/", "dir/ "})
    void uploadFile_shouldStoreAsUnnamed_whenFilenameHasNoUsableSegment(String filename) throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        MultipartFile file = fileOf(filename, 10L, PLAIN_BYTES);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        Attachment saved = fileStorageService.uploadFile(file, message, room, uploader, null);

        assertThat(saved.getOriginalFileName()).isEqualTo("unnamed");
    }

    @Test
    void uploadFile_shouldThrowFileStorageException_whenInputStreamFails() throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.txt");
        when(file.getSize()).thenReturn(10L);
        // Sniffing swallows the IOException (returns null); the copy re-reads and surfaces it.
        when(file.getInputStream()).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> fileStorageService.uploadFile(file, message, room, uploader, null))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("Failed to store file");

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void uploadFile_shouldStoreOctetStreamDefault_whenSniffingFailsButCopySucceeds() throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("x.bin");
        when(file.getSize()).thenReturn(10L);
        // First read (sniffing) fails so sniffContentType hits its catch; the copy re-reads a fresh
        // stream and succeeds. A null sniff falls back to the octet-stream default — an empty-string
        // return instead would be stored verbatim.
        when(file.getInputStream())
                .thenThrow(new IOException("sniff fail"))
                .thenReturn(new ByteArrayInputStream(PLAIN_BYTES));
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        Attachment saved = fileStorageService.uploadFile(file, message, room, uploader, null);

        assertThat(saved.getContentType()).isEqualTo("application/octet-stream");
    }

    @ParameterizedTest(name = "completion status={0} -> orphan file remains={1}")
    @MethodSource("completionStatusCases")
    void uploadFile_shouldDeleteOrphanOnlyOnRollback_whenTransactionCompletes(int status, boolean fileRemains)
            throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        MultipartFile file = fileOf("doc.txt", 20L, PLAIN_BYTES);
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            Attachment saved = fileStorageService.uploadFile(file, message, room, uploader, null);
            Path stored = Paths.get(saved.getStoragePath());
            assertThat(Files.exists(stored)).isTrue();

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(status);
            }

            assertThat(Files.exists(stored)).isEqualTo(fileRemains);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    static Stream<Arguments> completionStatusCases() {
        return Stream.of(
                Arguments.of(TransactionSynchronization.STATUS_COMMITTED, true),
                Arguments.of(TransactionSynchronization.STATUS_ROLLED_BACK, false),
                Arguments.of(TransactionSynchronization.STATUS_UNKNOWN, true));
    }

    // --- download ---

    @Test
    void downloadFile_attachmentInRoom_succeeds() throws IOException {
        Path roomDir = tempDir.resolve(room.getId().toString());
        Files.createDirectories(roomDir);
        Path filePath = roomDir.resolve("stored-file.pdf");
        Files.writeString(filePath, "file-content");

        UUID attachmentId = UUID.randomUUID();
        Attachment attachment = Attachment.builder()
                .id(attachmentId)
                .message(message)
                .originalFileName("report.pdf")
                .storagePath(filePath.toString())
                .fileSize(12L)
                .contentType("application/octet-stream")
                .build();
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));

        Resource result = fileStorageService.downloadFile(attachmentId, room, uploader);

        assertThat(result).isNotNull();
        assertThat(result.exists()).isTrue();
    }

    @Test
    void downloadFile_attachmentInDifferentRoom_throwsNotFound() {
        Room otherRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("other")
                .visibility(RoomVisibility.PUBLIC)
                .owner(uploader)
                .nextWatermark(1L)
                .build();
        UUID attachmentId = UUID.randomUUID();
        // Attachment's message belongs to `room`, but the request names `otherRoom` (R1-15).
        Attachment attachment = Attachment.builder()
                .id(attachmentId)
                .message(message)
                .originalFileName("report.pdf")
                .storagePath("/x")
                .fileSize(1L)
                .build();
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

    @Test
    void downloadFile_shouldThrowFileStorageException_whenFileMissingOnDisk() {
        UUID attachmentId = UUID.randomUUID();
        Path missing = tempDir.resolve("gone.pdf");
        Attachment attachment = Attachment.builder()
                .id(attachmentId)
                .message(message)
                .originalFileName("gone.pdf")
                .storagePath(missing.toString())
                .fileSize(1L)
                .build();
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> fileStorageService.downloadFile(attachmentId, room, uploader))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("File not found on disk");
    }

    // --- deferred file cleanup ---

    @Test
    void deleteFilesAfterCommit_shouldRegisterNothing_whenCollectionEmpty() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            fileStorageService.deleteFilesAfterCommit(List.of());

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteFilesAfterCommit_shouldDeleteImmediately_whenNoSynchronizationActive() throws IOException {
        Path target = Files.createFile(tempDir.resolve("now.txt"));

        fileStorageService.deleteFilesAfterCommit(List.of(target));

        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void deleteFilesAfterCommit_shouldDeferUntilCommit_whenSynchronizationActive() throws IOException {
        Path target = Files.createFile(tempDir.resolve("commit-me.txt"));

        TransactionSynchronizationManager.initSynchronization();
        try {
            fileStorageService.deleteFilesAfterCommit(List.of(target));
            assertThat(Files.exists(target)).isTrue();

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            assertThat(Files.exists(target)).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteFilesAfterCommit_shouldSwallowIOException_whenPathIsNonEmptyDirectory() throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("stubborn"));
        Files.writeString(dir.resolve("child.txt"), "x");

        // deleteIfExists throws DirectoryNotEmptyException; it is caught and logged, not propagated.
        fileStorageService.deleteFilesAfterCommit(List.of(dir));

        assertThat(Files.exists(dir)).isTrue();
    }

    @Test
    void deleteRoomDirectoryAfterCommit_shouldRemoveDirectoryTree_whenNoSynchronizationActive() throws IOException {
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());
        UUID roomId = UUID.randomUUID();
        Path roomDir = tempDir.resolve(roomId.toString());
        Files.createDirectories(roomDir);
        Files.writeString(roomDir.resolve("a.txt"), "a");
        Files.writeString(roomDir.resolve("b.txt"), "b");

        fileStorageService.deleteRoomDirectoryAfterCommit(roomId);

        assertThat(Files.exists(roomDir)).isFalse();
    }
}
