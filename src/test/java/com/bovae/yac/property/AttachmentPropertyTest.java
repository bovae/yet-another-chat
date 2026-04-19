package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.FileStorageException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FileStorageService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for FileStorageService: attachment upload, access control,
 * and oversized file rejection.
 *
 * Validates: Requirements 15.1, 15.2, 15.5, 15.6, 15.8, 15.9
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class AttachmentPropertyTest {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private UserService userService;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomBanRepository roomBanRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    private Path tempStorageDir;

    @AfterTry
    void cleanup() throws IOException {
        attachmentRepository.deleteAll();
        messageRepository.findAll().forEach(m -> {
            if (m.getReplyTo() != null) {
                m.setReplyTo(null);
                messageRepository.save(m);
            }
        });
        messageRepository.deleteAll();
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();

        if (tempStorageDir != null && Files.exists(tempStorageDir)) {
            Files.walkFileTree(tempStorageDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            tempStorageDir = null;
        }
    }

    @Provide
    Arbitrary<String> validEmails() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(12)
                .map(local -> local.toLowerCase() + "@example.com");
    }

    @Provide
    Arbitrary<String> validUsernames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> validPasswords() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(8)
                .ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(30)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> fileNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(name -> name.toLowerCase() + ".txt");
    }

    @Provide
    Arbitrary<String> imageNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(name -> name.toLowerCase() + ".png");
    }

    @Provide
    Arbitrary<String> optionalComments() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings()
                        .withCharRange(' ', '~')
                        .ofMinLength(1)
                        .ofMaxLength(100)
        );
    }

    // Feature: online-chat-server, Property 21: Attachment upload and access control
    /**
     * Validates: Requirements 15.1, 15.2, 15.5, 15.6, 15.8, 15.9
     *
     * For any file upload within size limits (≤20MB for files, ≤3MB for images),
     * the system SHALL store the file, create an Attachment record preserving the
     * original file name and optional comment. Download SHALL succeed if and only
     * if the requester is a current Room Member.
     */
    @Property(tries = 20)
    void attachmentUploadAndAccessControl(
            @ForAll("validEmails") String ownerEmail,
            @ForAll("validUsernames") String ownerUsername,
            @ForAll("validPasswords") String ownerPassword,
            @ForAll("validEmails") String memberEmail,
            @ForAll("validUsernames") String memberUsername,
            @ForAll("validPasswords") String memberPassword,
            @ForAll("roomNames") String roomName,
            @ForAll("fileNames") String fileName,
            @ForAll("optionalComments") String comment
    ) {
        // Setup: create owner, member, and room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto ownerDto = userService.register(ownerEmail + suffix, ownerUsername + suffix, ownerPassword);
        User owner = userRepository.findById(ownerDto.id()).orElseThrow();
        UserDto memberDto = userService.register(memberEmail + suffix + "m", memberUsername + suffix + "m", memberPassword);
        User member = userRepository.findById(memberDto.id()).orElseThrow();

        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Attachment test room", RoomVisibility.PUBLIC, owner).id());
        roomMemberService.joinPublicRoom(room, member);

        // Create a message to attach the file to
        Message message = messageService.sendMessage(room, member, "File attachment test", null);

        // Create a valid file (under 20MB)
        byte[] fileContent = new byte[1024]; // 1KB file
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", fileName, "application/octet-stream", fileContent);

        // Step 1: Upload file as member → Attachment created with correct original name and comment
        Attachment attachment = fileStorageService.uploadFile(multipartFile, message, room, member, comment);

        assertThat(attachment).isNotNull();
        assertThat(attachment.getId()).isNotNull();
        assertThat(attachment.getOriginalFileName()).isEqualTo(fileName);
        assertThat(attachment.getComment()).isEqualTo(comment);
        assertThat(attachment.getFileSize()).isEqualTo(fileContent.length);
        assertThat(attachment.getContentType()).isEqualTo("application/octet-stream");

        // Verify persistence
        Attachment persisted = attachmentRepository.findById(attachment.getId()).orElseThrow();
        assertThat(persisted.getOriginalFileName()).isEqualTo(fileName);
        assertThat(persisted.getComment()).isEqualTo(comment);

        // Step 2: Download as member → succeeds
        Resource resource = fileStorageService.downloadFile(attachment.getId(), room, member);
        assertThat(resource).isNotNull();
        assertThat(resource.exists()).isTrue();

        // Download as owner (also a member) → succeeds
        Resource ownerResource = fileStorageService.downloadFile(attachment.getId(), room, owner);
        assertThat(ownerResource).isNotNull();
        assertThat(ownerResource.exists()).isTrue();

        // Step 3: Member leaves room, try download → ForbiddenException
        roomMemberService.leaveRoom(room, member);

        assertThatThrownBy(() -> fileStorageService.downloadFile(attachment.getId(), room, member))
                .isInstanceOf(ForbiddenException.class);
    }

    // Feature: online-chat-server, Property 21: Attachment upload and access control (oversized rejection)
    /**
     * Validates: Requirements 15.1, 15.2, 15.5, 15.6, 15.8, 15.9
     *
     * Test oversized file rejection: >20MB for files, >3MB for images.
     */
    @Property(tries = 20)
    void oversizedFileShallBeRejected(
            @ForAll("validEmails") String ownerEmail,
            @ForAll("validUsernames") String ownerUsername,
            @ForAll("validPasswords") String ownerPassword,
            @ForAll("roomNames") String roomName,
            @ForAll("imageNames") String imageName
    ) {
        // Setup: create owner and room
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UserDto ownerDto = userService.register(ownerEmail + suffix, ownerUsername + suffix, ownerPassword);
        User owner = userRepository.findById(ownerDto.id()).orElseThrow();

        String uniqueRoomName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(roomService.createRoom(uniqueRoomName, "Oversized test room", RoomVisibility.PUBLIC, owner).id());

        Message message = messageService.sendMessage(room, owner, "Oversized file test", null);

        // Test oversized generic file (>20MB)
        byte[] oversizedFileContent = new byte[20 * 1024 * 1024 + 1]; // 20MB + 1 byte
        MockMultipartFile oversizedFile = new MockMultipartFile(
                "file", "large.dat", "application/octet-stream", oversizedFileContent);

        assertThatThrownBy(() -> fileStorageService.uploadFile(oversizedFile, message, room, owner, null))
                .isInstanceOf(FileStorageException.class);

        // Test oversized image (>3MB)
        byte[] oversizedImageContent = new byte[3 * 1024 * 1024 + 1]; // 3MB + 1 byte
        MockMultipartFile oversizedImage = new MockMultipartFile(
                "file", imageName, "image/png", oversizedImageContent);

        assertThatThrownBy(() -> fileStorageService.uploadFile(oversizedImage, message, room, owner, null))
                .isInstanceOf(FileStorageException.class);

        // No attachments should have been persisted
        assertThat(attachmentRepository.findByRoom(room)).isEmpty();
    }
}
