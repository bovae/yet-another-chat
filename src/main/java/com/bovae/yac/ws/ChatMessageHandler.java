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
import com.bovae.yac.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageService messageService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;

    @MessageMapping("/chat.send")
    public void sendMessage(@Valid ChatMessageRequest request, Principal principal) {
        User sender = resolveUser(principal);
        Room room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Room not found: %s".formatted(request.roomId())));

        Message replyTo = null;
        if (request.replyToId() != null) {
            replyTo = messageRepository.findById(request.replyToId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reply-to message not found: %s".formatted(request.replyToId())));
        }

        Message message = messageService.sendMessage(room, sender, request.content(), replyTo);

        ChatMessageResponse response = new ChatMessageResponse(
                message.getId(),
                room.getId(),
                sender.getId(),
                sender.getUsername(),
                message.getContent(),
                request.replyToId(),
                message.isEdited(),
                message.getWatermark(),
                message.getCreatedAt()
        );

        messagingTemplate.convertAndSend("/topic/room." + room.getId(), response);

        LOG.debug("Broadcast message to /topic/room.{}: messageId={}, sender={}",
                room.getId(), message.getId(), sender.getUsername());
    }

    @MessageExceptionHandler
    public void handleException(Exception ex, Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        LOG.warn("WebSocket error for user {}: {}", username, ex.getMessage());

        int status = 400;
        if (ex instanceof ForbiddenException) {
            status = 403;
        } else if (ex instanceof ResourceNotFoundException) {
            status = 404;
        }

        ErrorResponse error = new ErrorResponse(Instant.now(), status, ex.getMessage(), null);
        if (principal != null) {
            messagingTemplate.convertAndSendToUser(
                    principal.getName(), "/queue/errors", error);
        }
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: %s".formatted(principal.getName())));
    }
}
