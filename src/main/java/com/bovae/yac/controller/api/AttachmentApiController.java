package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FileStorageService;
import com.bovae.yac.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
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

import java.security.Principal;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/attachments")
@RequiredArgsConstructor
public class AttachmentApiController {

    private final FileStorageService fileStorageService;
    private final RoomService roomService;
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

        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message not found: %s".formatted(messageId)));

        fileStorageService.uploadFile(file, message, room, user, comment);

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable UUID roomId,
            @PathVariable UUID id,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        Attachment attachment = attachmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Attachment not found: %s".formatted(id)));

        Resource resource = fileStorageService.downloadFile(id, room, user);

        String contentType = attachment.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        String disposition = contentType.startsWith("image/") ? "inline" : "attachment";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition + "; filename=\"%s\"".formatted(attachment.getOriginalFileName()))
                .body(resource);
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
