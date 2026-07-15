package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FileStorageService;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/attachments")
@RequiredArgsConstructor
public class AttachmentApiController {

    private final FileStorageService fileStorageService;
    private final MessageService messageService;
    private final MessageBroadcastService messageBroadcastService;
    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;

    @PostMapping
    public ResponseEntity<Void> uploadFile(
            @PathVariable UUID roomId,
            @RequestParam("file") MultipartFile file,
            @RequestParam UUID messageId,
            @RequestParam(required = false) String comment,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        // Same send-time guards as posting a message (R1-27).
        messageService.assertCanPost(room, user);

        Message message = messageRepository
                .findByIdWithSender(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: %s".formatted(messageId)));

        fileStorageService.uploadFile(file, message, room, user, comment);

        // Broadcast the message (now with its attachment) through the shared path (R1-03).
        ChatMessageResponse response = messageService.getMessageResponse(messageId);
        messageBroadcastService.broadcastNewMessage(room, user, response);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable UUID roomId, @PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        roomMemberService.requireCanRead(room, user); // R1-15

        Attachment attachment = attachmentRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: %s".formatted(id)));

        Resource resource = fileStorageService.downloadFile(id, room, user);

        String contentType = attachment.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        // Always attachment disposition so uploaded content cannot execute in the app origin;
        // images still render via <img> tags (R1-13, R1-33). Filename encoded, never concatenated.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.getOriginalFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private User resolveUser(Principal principal) {
        return userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
