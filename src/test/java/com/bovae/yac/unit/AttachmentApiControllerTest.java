package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.AttachmentApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FileStorageService;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** Unit tests for {@link AttachmentApiController}. */
@ExtendWith(MockitoExtension.class)
class AttachmentApiControllerTest {

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private MessageService messageService;

    @Mock
    private MessageBroadcastService messageBroadcastService;

    @Mock
    private RoomService roomService;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private MultipartFile file;

    @Mock
    private Resource resource;

    @Mock
    private Principal principal;

    @InjectMocks
    private AttachmentApiController controller;

    private User user;
    private Room room;
    private UUID roomId;
    private UUID messageId;
    private UUID attachmentId;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        attachmentId = UUID.randomUUID();

        user = User.builder()
                .id(UUID.randomUUID())
                .email("caller@test.com")
                .username("caller")
                .displayName("Caller")
                .build();

        room = Room.builder()
                .id(roomId)
                .name("room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(user)
                .build();

        lenient().when(principal.getName()).thenReturn(user.getEmail());
        lenient().when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    // --- uploadFile ---

    @Test
    void uploadFile_shouldReturnCreatedStoreAndBroadcast_whenMessageFound() {
        Message message = Message.builder()
                .id(messageId)
                .room(room)
                .sender(user)
                .content("hello")
                .build();
        ChatMessageResponse response = chatMessageResponse();
        when(roomService.getRoomById(roomId)).thenReturn(room);
        when(messageRepository.findByIdWithSender(messageId)).thenReturn(Optional.of(message));
        when(messageService.getMessageResponse(messageId)).thenReturn(response);

        ResponseEntity<Void> result = controller.uploadFile(roomId, file, messageId, "a comment", principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(messageService).assertCanPost(room, user);
        verify(fileStorageService).uploadFile(file, message, room, user, "a comment");
        verify(messageBroadcastService).broadcastNewMessage(room, user, response);
    }

    @Test
    void uploadFile_shouldThrowNotFound_whenMessageMissing() {
        when(roomService.getRoomById(roomId)).thenReturn(room);
        when(messageRepository.findByIdWithSender(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.uploadFile(roomId, file, messageId, null, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(messageId.toString());

        verify(fileStorageService, never()).uploadFile(any(), any(), any(), any(), any());
        verify(messageBroadcastService, never()).broadcastNewMessage(any(), any(), any());
    }

    @Test
    void uploadFile_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.uploadFile(roomId, file, messageId, null, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(roomService, never()).getRoomById(roomId);
    }

    // --- downloadFile ---

    @Test
    void downloadFile_shouldReturnResourceWithDeclaredContentType_whenContentTypePresent() {
        Attachment attachment = Attachment.builder()
                .id(attachmentId)
                .originalFileName("photo.png")
                .contentType("image/png")
                .build();
        when(roomService.getRoomById(roomId)).thenReturn(room);
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));
        when(fileStorageService.downloadFile(attachmentId, room, user)).thenReturn(resource);

        ResponseEntity<Resource> response = controller.downloadFile(roomId, attachmentId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(resource);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/png"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("photo.png");
        verify(roomMemberService).requireCanRead(room, user);
    }

    @ParameterizedTest(name = "contentType=[{0}] → application/octet-stream")
    @NullAndEmptySource
    void downloadFile_shouldFallBackToOctetStream_whenContentTypeMissing(String contentType) {
        Attachment attachment = Attachment.builder()
                .id(attachmentId)
                .originalFileName("data.bin")
                .contentType(contentType)
                .build();
        when(roomService.getRoomById(roomId)).thenReturn(room);
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment));
        when(fileStorageService.downloadFile(attachmentId, room, user)).thenReturn(resource);

        ResponseEntity<Resource> response = controller.downloadFile(roomId, attachmentId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void downloadFile_shouldThrowNotFound_whenAttachmentMissing() {
        when(roomService.getRoomById(roomId)).thenReturn(room);
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.downloadFile(roomId, attachmentId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(attachmentId.toString());

        verify(fileStorageService, never()).downloadFile(any(), any(), any());
    }

    private ChatMessageResponse chatMessageResponse() {
        return new ChatMessageResponse(
                messageId,
                roomId,
                user.getId(),
                "caller",
                "Caller",
                "hello",
                null,
                null,
                null,
                false,
                1L,
                Instant.now(),
                List.of());
    }
}
