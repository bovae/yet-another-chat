package com.bovae.yac.ws;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.ChatMessageRequest;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.ErrorResponse;
import com.bovae.yac.model.dto.NotificationEvent;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageService messageService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final NotificationService notificationService;

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
                null,
                null,
                message.isEdited(),
                message.getWatermark(),
                message.getCreatedAt(),
                List.of()
        );

        messagingTemplate.convertAndSend("/topic/room." + room.getId(), response);

        LOG.debug("Broadcast message to /topic/room.{}: messageId={}, sender={}",
                room.getId(), message.getId(), sender.getUsername());

        List<RoomMember> members = roomMemberRepository.findByRoomWithUsers(room);
        for (RoomMember member : members) {
            if (!member.getUser().getId().equals(sender.getId())) {
                int unread = notificationService.computeUnreadCount(member.getUser(), room);
                NotificationEvent event = new NotificationEvent(
                        "UNREAD_UPDATE", room.getId(), room.getName(), unread);
                notificationService.broadcastNotification(member.getUser(), event);
            }
        }
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
