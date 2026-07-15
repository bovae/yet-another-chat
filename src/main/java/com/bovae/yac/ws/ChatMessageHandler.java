package com.bovae.yac.ws;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.ErrorResponse;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageBroadcastService;
import com.bovae.yac.service.MessageService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageService messageService;
    private final MessageBroadcastService messageBroadcastService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;

    @MessageMapping("/chat.send")
    public void sendMessage(@Valid ChatMessageRequest request, Principal principal) {
        User sender = resolveUser(principal);
        Room room = roomRepository
                .findById(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: %s".formatted(request.roomId())));

        Message replyTo = null;
        if (request.replyToId() != null) {
            replyTo = messageRepository
                    .findByIdWithSenderAndReplyTo(request.replyToId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reply-to message not found: %s".formatted(request.replyToId())));
        }

        Message message = messageService.sendMessage(room, sender, request.content(), replyTo);

        String replyToSenderUsername = (replyTo != null && replyTo.getSender() != null)
                ? replyTo.getSender().getUsername()
                : null;
        String replyToContentSnippet = replyTo != null
                ? (replyTo.getContent().length() > 100 ? replyTo.getContent().substring(0, 100) : replyTo.getContent())
                : null;

        ChatMessageResponse response = new ChatMessageResponse(
                message.getId(),
                room.getId(),
                sender.getId(),
                sender.getUsername(),
                sender.getDisplayName(),
                message.getContent(),
                request.replyToId(),
                replyToSenderUsername,
                replyToContentSnippet,
                message.isEdited(),
                message.getWatermark(),
                message.getCreatedAt(),
                List.of());

        messageBroadcastService.broadcastNewMessage(room, sender, response);
    }

    @MessageExceptionHandler
    public void handleException(Exception ex, Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";

        int status;
        String message;
        if (ex instanceof ForbiddenException) {
            status = 403;
            message = ex.getMessage();
        } else if (ex instanceof ResourceNotFoundException) {
            status = 404;
            message = ex.getMessage();
        } else if (ex instanceof IllegalArgumentException || ex instanceof ConstraintViolationException) {
            status = 400;
            message = ex.getMessage();
        } else {
            // Unexpected failures never leak internals to the client (R1-37).
            status = 500;
            message = "An unexpected error occurred";
            LOG.error("Unexpected WebSocket error for user {}", username, ex);
        }

        if (status != 500) {
            LOG.warn("WebSocket error for user {}: {}", username, ex.getMessage());
        }

        // ex.getMessage() may be null; keep the client-facing message non-null like the REST handler does.
        String detail = Objects.requireNonNullElse(message, "An unexpected error occurred");
        ErrorResponse error = new ErrorResponse(Instant.now(), status, detail, null);
        if (principal != null) {
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", error);
        }
    }

    private User resolveUser(Principal principal) {
        return userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: %s".formatted(principal.getName())));
    }
}
