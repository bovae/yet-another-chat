package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.EditMessageRequest;
import com.bovae.yac.model.dto.MessageDeletedEvent;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class MessageApiController {

    private final MessageService messageService;
    private final MessageBroadcastService messageBroadcastService;
    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @GetMapping
    public ResponseEntity<MessagePage> getMessages(
            @PathVariable UUID roomId,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = "50") int size,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);
        roomMemberService.requireCanRead(room, user); // R1-08

        int effectiveSize = Math.min(Math.max(size, 1), 100);
        // `after` = ascending reconnect catch-up; otherwise `before` = backward pagination (newest by default).
        MessagePage page = (after != null)
                ? messageService.getMessagesSince(room, after, effectiveSize)
                : messageService.getMessageHistory(room, before, effectiveSize);

        return ResponseEntity.ok(page);
    }

    @PostMapping
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @PathVariable UUID roomId, @Valid @RequestBody ChatMessageRequest request, Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        Message replyTo = null;
        if (request.replyToId() != null) {
            replyTo = messageRepository
                    .findByIdWithSender(request.replyToId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reply-to message not found: %s".formatted(request.replyToId())));
        }

        Message message = messageService.sendMessage(room, user, request.content(), replyTo);

        ChatMessageResponse response = toResponse(message);
        // REST sends broadcast through the same path as WS sends so viewers see them live (R1-03).
        messageBroadcastService.broadcastNewMessage(room, user, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ChatMessageResponse> editMessage(
            @PathVariable UUID roomId,
            @PathVariable UUID id,
            @Valid @RequestBody EditMessageRequest request,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        Message message = messageService.editMessage(id, user, request.content(), room);

        // Broadcast the edit so viewers update in place instead of dropping it as a duplicate (R1-04).
        messageBroadcastService.broadcastEdit(room, message.getId(), message.getContent());

        return ResponseEntity.ok(toResponse(message));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMessage(@PathVariable UUID roomId, @PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(roomId);

        messageService.deleteMessage(id, user, room);

        MessageDeletedEvent event = MessageDeletedEvent.of(id, roomId, user.getId());
        messagingTemplate.convertAndSend("/topic/room." + roomId, event);

        // Deleting a message can change unread counts, so recompute and re-fan-out (R1-57).
        messageBroadcastService.recomputeUnread(room);

        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Principal principal) {
        return userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }

    private ChatMessageResponse toResponse(Message message) {
        Message replyTo = message.getReplyTo();
        String replyToSenderUsername = null;
        String replyToContentSnippet = null;

        if (replyTo != null) {
            replyToSenderUsername =
                    replyTo.getSender() != null ? replyTo.getSender().getUsername() : "Deleted user";
            String content = replyTo.getContent();
            replyToContentSnippet = content.length() > 100 ? content.substring(0, 100) : content;
        }

        User sender = message.getSender();
        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                sender != null ? sender.getId() : null,
                sender != null ? sender.getUsername() : "Deleted user",
                sender != null ? sender.getDisplayName() : null,
                message.getContent(),
                replyTo != null ? replyTo.getId() : null,
                replyToSenderUsername,
                replyToContentSnippet,
                message.isEdited(),
                message.getWatermark(),
                message.getCreatedAt(),
                List.of());
    }
}
