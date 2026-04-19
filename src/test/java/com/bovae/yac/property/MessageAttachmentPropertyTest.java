package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.AttachmentInfo;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for message DTO attachment metadata.
 *
 * Validates: Requirements 15.5
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class MessageAttachmentPropertyTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @AfterTry
    void cleanup() {
        attachmentRepository.deleteAll();
        messageRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Provide
    Arbitrary<Integer> attachmentCounts() {
        return Arbitraries.integers().between(0, 5);
    }

    @Provide
    Arbitrary<String> contentTypes() {
        return Arbitraries.of(
                "image/png",
                "image/jpeg",
                "image/gif",
                "application/pdf",
                "text/plain",
                "application/octet-stream",
                "video/mp4"
        );
    }

    @Provide
    Arbitrary<String> fileNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20)
                .map(name -> name + ".dat");
    }

    @Provide
    Arbitrary<Long> fileSizes() {
        return Arbitraries.longs().between(1L, 20_000_000L);
    }

    // Feature: ui-completion-and-fixes, Property 13: Message DTO includes attachment metadata
    /**
     * Validates: Requirements 15.5
     *
     * ChatMessageResponse.attachments contains matching id, originalFileName, contentType, fileSize
     * for each attachment; empty list when no attachments.
     */
    @Property(tries = 20)
    void messageDtoIncludesAttachmentMetadata(
            @ForAll("attachmentCounts") int attachmentCount
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User sender = userRepository.findById(
                userService.register("sender" + suffix + "@example.com", "sender" + suffix, "password123").id()
        ).orElseThrow();

        String roomName = "room-" + UUID.randomUUID();
        Room room = roomService.getRoomById(
                roomService.createRoom(roomName, "test room", RoomVisibility.PUBLIC, sender).id()
        );

        Message message = messageService.sendMessage(room, sender, "test message " + suffix, null);

        // Create attachments directly via repository
        List<Attachment> createdAttachments = new ArrayList<>();
        for (int i = 0; i < attachmentCount; i++) {
            Attachment attachment = Attachment.builder()
                    .message(message)
                    .originalFileName("file-" + i + "-" + suffix + ".dat")
                    .storagePath("/storage/" + UUID.randomUUID())
                    .contentType(randomContentType(i))
                    .fileSize(1000L + i)
                    .build();
            createdAttachments.add(attachmentRepository.save(attachment));
        }

        // Fetch message history and verify attachment metadata
        MessagePage page = messageService.getMessageHistory(room, 0L, 50);
        assertThat(page.messages()).isNotEmpty();

        ChatMessageResponse response = page.messages().stream()
                .filter(m -> m.id().equals(message.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(response.attachments()).hasSize(attachmentCount);

        for (Attachment expected : createdAttachments) {
            AttachmentInfo actual = response.attachments().stream()
                    .filter(a -> a.id().equals(expected.getId()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "Missing attachment in response: %s".formatted(expected.getId())));

            assertThat(actual.originalFileName()).isEqualTo(expected.getOriginalFileName());
            assertThat(actual.contentType()).isEqualTo(expected.getContentType());
            assertThat(actual.fileSize()).isEqualTo(expected.getFileSize());
        }
    }

    private String randomContentType(int index) {
        String[] types = {"image/png", "image/jpeg", "application/pdf", "text/plain", "video/mp4", "application/octet-stream"};
        return types[index % types.length];
    }
}
