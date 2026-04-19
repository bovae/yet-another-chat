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
import com.bovae.yac.service.RoomMemberService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FileStorageService}.
 *
 * <p>Validates Correctness Properties: CP 21.
 * <p>Requirements: 5.5, 5.6, 5.7.
 */
@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private FileStorageProperties fileStorageProperties;

    @InjectMocks
    private FileStorageService fileStorageService;

    private User uploader;
    private User nonMember;
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

        nonMember = User.builder()
                .id(UUID.randomUUID())
                .email("bob@test.com")
                .username("bob")
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

    // -----------------------------------------------------------------------
    // uploadFile stores file and creates Attachment preserving original name (CP 21)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 21, Requirement 5.5: uploadFile stores the file on disk and
     * creates an Attachment record preserving the original file name.
     */
    @Test
    void uploadFile_storesFileAndCreatesAttachmentPreservingOriginalName() throws IOException {
        when(roomMemberService.isMember(room, uploader)).thenReturn(true);
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());

        MultipartFile multipartFile = mock(MultipartFile.class);
        when(multipartFile.getOriginalFilename()).thenReturn("report.pdf");
        when(multipartFile.getSize()).thenReturn(1024L);
        when(multipartFile.getContentType()).thenReturn("application/pdf");
        when(multipartFile.getInputStream()).thenReturn(new ByteArrayInputStream("file-content".getBytes()));

        Attachment savedAttachment = Attachment.builder()
                .id(UUID.randomUUID())
                .message(message)
                .originalFileName("report.pdf")
                .storagePath("/some/path")
                .fileSize(1024L)
                .contentType("application/pdf")
                .comment("my comment")
                .build();
        when(attachmentRepository.save(any(Attachment.class))).thenReturn(savedAttachment);

        Attachment result = fileStorageService.uploadFile(multipartFile, message, room, uploader, "my comment");

        assertThat(result).isNotNull();
        assertThat(result.getOriginalFileName()).isEqualTo("report.pdf");

        // Verify the attachment was saved with the original file name preserved
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepository).save(captor.capture());
        Attachment captured = captor.getValue();
        assertThat(captured.getOriginalFileName()).isEqualTo("report.pdf");
        assertThat(captured.getMessage()).isEqualTo(message);
        assertThat(captured.getFileSize()).isEqualTo(1024L);
        assertThat(captured.getContentType()).isEqualTo("application/pdf");
        assertThat(captured.getComment()).isEqualTo("my comment");

        // Verify a file was actually written to the room directory
        Path roomDir = tempDir.resolve(room.getId().toString());
        assertThat(Files.exists(roomDir)).isTrue();
        assertThat(Files.list(roomDir).count()).isEqualTo(1);
    }

    /**
     * Validates CP 21: uploadFile by a non-member throws ForbiddenException.
     */
    @Test
    void uploadFile_nonMember_throwsForbiddenException() {
        when(roomMemberService.isMember(room, nonMember)).thenReturn(false);

        MultipartFile multipartFile = mock(MultipartFile.class);

        assertThatThrownBy(() -> fileStorageService.uploadFile(multipartFile, message, room, nonMember, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not a member");

        verify(attachmentRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // uploadFile rejects oversized files (CP 21)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 21, Requirement 5.6: uploadFile rejects non-image files exceeding 20 MB.
     */
    @Test
    void uploadFile_fileExceeding20MB_throwsFileStorageException() {
        when(roomMemberService.isMember(room, uploader)).thenReturn(true);

        MultipartFile multipartFile = mock(MultipartFile.class);
        long oversizedBytes = 20L * 1024 * 1024 + 1;
        when(multipartFile.getSize()).thenReturn(oversizedBytes);
        when(multipartFile.getContentType()).thenReturn("application/pdf");

        assertThatThrownBy(() -> fileStorageService.uploadFile(multipartFile, message, room, uploader, null))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("20 MB");

        verify(attachmentRepository, never()).save(any());
    }

    /**
     * Validates CP 21, Requirement 5.6: uploadFile rejects images exceeding 3 MB.
     */
    @Test
    void uploadFile_imageExceeding3MB_throwsFileStorageException() {
        when(roomMemberService.isMember(room, uploader)).thenReturn(true);

        MultipartFile multipartFile = mock(MultipartFile.class);
        long oversizedImageBytes = 3L * 1024 * 1024 + 1;
        when(multipartFile.getSize()).thenReturn(oversizedImageBytes);
        when(multipartFile.getContentType()).thenReturn("image/png");

        assertThatThrownBy(() -> fileStorageService.uploadFile(multipartFile, message, room, uploader, null))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("3 MB");

        verify(attachmentRepository, never()).save(any());
    }

    /**
     * Validates CP 21: uploadFile accepts a non-image file at exactly 20 MB (boundary).
     */
    @Test
    void uploadFile_fileExactly20MB_succeeds() throws IOException {
        when(roomMemberService.isMember(room, uploader)).thenReturn(true);
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());

        MultipartFile multipartFile = mock(MultipartFile.class);
        long exactLimit = 20L * 1024 * 1024;
        when(multipartFile.getSize()).thenReturn(exactLimit);
        when(multipartFile.getContentType()).thenReturn("application/pdf");
        when(multipartFile.getOriginalFilename()).thenReturn("big.pdf");
        when(multipartFile.getInputStream()).thenReturn(new ByteArrayInputStream("data".getBytes()));

        Attachment savedAttachment = Attachment.builder()
                .id(UUID.randomUUID())
                .message(message)
                .originalFileName("big.pdf")
                .storagePath("/path")
                .fileSize(exactLimit)
                .contentType("application/pdf")
                .build();
        when(attachmentRepository.save(any(Attachment.class))).thenReturn(savedAttachment);

        Attachment result = fileStorageService.uploadFile(multipartFile, message, room, uploader, null);

        assertThat(result).isNotNull();
        verify(attachmentRepository).save(any(Attachment.class));
    }

    /**
     * Validates CP 21: uploadFile accepts an image at exactly 3 MB (boundary).
     */
    @Test
    void uploadFile_imageExactly3MB_succeeds() throws IOException {
        when(roomMemberService.isMember(room, uploader)).thenReturn(true);
        when(fileStorageProperties.basePath()).thenReturn(tempDir.toString());

        MultipartFile multipartFile = mock(MultipartFile.class);
        long exactImageLimit = 3L * 1024 * 1024;
        when(multipartFile.getSize()).thenReturn(exactImageLimit);
        when(multipartFile.getContentType()).thenReturn("image/jpeg");
        when(multipartFile.getOriginalFilename()).thenReturn("photo.jpg");
        when(multipartFile.getInputStream()).thenReturn(new ByteArrayInputStream("img".getBytes()));

        Attachment savedAttachment = Attachment.builder()
                .id(UUID.randomUUID())
                .message(message)
                .originalFileName("photo.jpg")
                .storagePath("/path")
                .fileSize(exactImageLimit)
                .contentType("image/jpeg")
                .build();
        when(attachmentRepository.save(any(Attachment.class))).thenReturn(savedAttachment);

        Attachment result = fileStorageService.uploadFile(multipartFile, message, room, uploader, null);

        assertThat(result).isNotNull();
        verify(attachmentRepository).save(any(Attachment.class));
    }

    // -----------------------------------------------------------------------
    // downloadFile succeeds for member, throws ForbiddenException for non-member (CP 21)
    // -----------------------------------------------------------------------

    /**
     * Validates CP 21, Requirement 5.7: downloadFile succeeds for a room member.
     */
    @Test
    void downloadFile_roomMember_succeeds() throws IOException {
        when(roomMemberService.isMember(room, uploader)).thenReturn(true);

        // Create a real file on disk so UrlResource can resolve it
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
                .contentType("application/pdf")
                .build();
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));

        Resource result = fileStorageService.downloadFile(attachmentId, room, uploader);

        assertThat(result).isNotNull();
        assertThat(result.exists()).isTrue();
    }

    /**
     * Validates CP 21, Requirement 5.7: downloadFile throws ForbiddenException for a non-member.
     */
    @Test
    void downloadFile_nonMember_throwsForbiddenException() {
        when(roomMemberService.isMember(room, nonMember)).thenReturn(false);

        UUID attachmentId = UUID.randomUUID();

        assertThatThrownBy(() -> fileStorageService.downloadFile(attachmentId, room, nonMember))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not a member");

        verify(attachmentRepository, never()).findById(any());
    }

    /**
     * Validates CP 21: downloadFile throws ResourceNotFoundException for non-existent attachment.
     */
    @Test
    void downloadFile_nonExistentAttachment_throwsResourceNotFoundException() {
        when(roomMemberService.isMember(room, uploader)).thenReturn(true);

        UUID attachmentId = UUID.randomUUID();
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileStorageService.downloadFile(attachmentId, room, uploader))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Attachment not found");
    }
}
