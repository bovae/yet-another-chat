package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class MessageApiController {

    private final MessageService messageService;
    private final RoomService roomService;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;

    @GetMapping
    public ResponseEntity<MessagePage> getMessages(
            @PathVariable UUID roomId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int size,
            Principal principal) {
        resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        int effectiveSize = Math.min(Math.max(size, 1), 100);
        MessagePage page = messageService.getMessageHistory(room, cursor, effectiveSize);

        return ResponseEntity.ok(page);
    }

    @PostMapping
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @PathVariable UUID roomId,
            @Valid @RequestBody ChatMessageRequest request,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        Message replyTo = null;
        if (request.replyToId() != null) {
            replyTo = messageRepository.findById(request.replyToId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reply-to message not found: %s".formatted(request.replyToId())));
        }

        Message message = messageService.sendMessage(room, user, request.content(), replyTo);

        ChatMessageResponse response = toResponse(message);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ChatMessageResponse> editMessage(
            @PathVariable UUID roomId,
            @PathVariable UUID id,
            @Valid @RequestBody ChatMessageRequest request,
            Principal principal) {
        User user = resolveUser(principal);
        roomService.getRoomById(roomId);

        Message message = messageService.editMessage(id, user, request.content());

        return ResponseEntity.ok(toResponse(message));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable UUID roomId,
            @PathVariable UUID id,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        messageService.deleteMessage(id, user, room);

        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }

    private ChatMessageResponse toResponse(Message message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                message.getSender().getId(),
                message.getSender().getUsername(),
                message.getContent(),
                message.getReplyTo() != null ? message.getReplyTo().getId() : null,
                message.isEdited(),
                message.getWatermark(),
                message.getCreatedAt(),
                List.of()
        );
    }
}
