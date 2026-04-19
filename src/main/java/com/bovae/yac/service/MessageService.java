package com.bovae.yac.service;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.AttachmentInfo;
import com.bovae.yac.model.dto.ChatMessageResponse;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {

    private static final int MAX_CONTENT_BYTES = 3072;

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberService roomMemberService;
    private final UserBanService userBanService;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final AttachmentRepository attachmentRepository;

    @Transactional
    public Message sendMessage(Room room, User sender, String content, Message replyTo) {
        if (!roomMemberService.isMember(room, sender)) {
            throw new ForbiddenException("User is not a member of this room");
        }

        validateContentSize(content);

        if (room.getVisibility() == RoomVisibility.DIRECT) {
            checkDirectChatBan(room, sender);
        }

        Long watermark = room.getNextWatermark();
        room.setNextWatermark(watermark + 1);
        roomRepository.save(room);

        Message message = Message.builder()
                .room(room)
                .sender(sender)
                .content(content)
                .replyTo(replyTo)
                .edited(false)
                .watermark(watermark)
                .build();

        message = messageRepository.save(message);

        LOG.info("Message sent: messageId={}, roomId={}, senderId={}, watermark={}",
                message.getId(), room.getId(), sender.getId(), watermark);

        return message;
    }

    @Transactional
    public Message editMessage(UUID messageId, User author, String newContent) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message not found: %s".formatted(messageId)));

        if (!message.getSender().getId().equals(author.getId())) {
            throw new ForbiddenException("Only the author can edit this message");
        }

        validateContentSize(newContent);

        message.setContent(newContent);
        message.setEdited(true);

        message = messageRepository.save(message);

        LOG.info("Message edited: messageId={}, authorId={}", messageId, author.getId());

        return message;
    }

    @Transactional
    public void deleteMessage(UUID messageId, User requestingUser, Room room) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message not found: %s".formatted(messageId)));

        boolean isAuthor = message.getSender().getId().equals(requestingUser.getId());

        if (!isAuthor) {
            RoomMember member = roomMemberRepository.findById(
                    new RoomMemberId(room.getId(), requestingUser.getId()))
                    .orElseThrow(() -> new ForbiddenException("User is not a member of this room"));

            if (member.getRole() != RoomRole.ADMIN && member.getRole() != RoomRole.OWNER) {
                throw new ForbiddenException("Only the author or an admin can delete this message");
            }
        }

        messageRepository.delete(message);

        LOG.info("Message deleted: messageId={}, deletedBy={}", messageId, requestingUser.getId());
    }

    @Transactional(readOnly = true)
    public MessagePage getMessageHistory(Room room, Long cursor, int size) {
        long effectiveCursor = (cursor != null) ? cursor : 0L;

        List<Message> messages = messageRepository.findByRoomAndWatermarkGreaterThanOrderByWatermarkAsc(
                room, effectiveCursor, PageRequest.of(0, size + 1));

        boolean hasMore = messages.size() > size;
        if (hasMore) {
            messages = messages.subList(0, size);
        }

        // Batch-load attachments for all messages to avoid N+1 queries
        List<UUID> messageIds = messages.stream()
                .map(Message::getId)
                .toList();

        Map<UUID, List<AttachmentInfo>> attachmentsByMessageId;
        if (messageIds.isEmpty()) {
            attachmentsByMessageId = Collections.emptyMap();
        } else {
            attachmentsByMessageId = attachmentRepository.findByMessageIdIn(messageIds)
                    .stream()
                    .collect(Collectors.groupingBy(
                            a -> a.getMessage().getId(),
                            Collectors.mapping(this::toAttachmentInfo, Collectors.toList())
                    ));
        }

        List<ChatMessageResponse> responseMessages = messages.stream()
                .map(msg -> toResponse(msg, attachmentsByMessageId.getOrDefault(msg.getId(), List.of())))
                .toList();

        Long nextCursor = messages.isEmpty() ? null : messages.getLast().getWatermark();

        return new MessagePage(responseMessages, nextCursor, hasMore);
    }

    private ChatMessageResponse toResponse(Message message) {
        List<AttachmentInfo> attachmentInfos = attachmentRepository.findByMessageId(message.getId())
                .stream()
                .map(this::toAttachmentInfo)
                .toList();

        return toResponse(message, attachmentInfos);
    }

    private ChatMessageResponse toResponse(Message message, List<AttachmentInfo> attachments) {
        Message replyTo = message.getReplyTo();
        String replyToSenderUsername = null;
        String replyToContentSnippet = null;

        if (replyTo != null) {
            replyToSenderUsername = replyTo.getSender().getUsername();
            String content = replyTo.getContent();
            replyToContentSnippet = content.length() > 100 ? content.substring(0, 100) : content;
        }

        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                message.getSender().getId(),
                message.getSender().getUsername(),
                message.getContent(),
                replyTo != null ? replyTo.getId() : null,
                replyToSenderUsername,
                replyToContentSnippet,
                message.isEdited(),
                message.getWatermark(),
                message.getCreatedAt(),
                attachments
        );
    }

    private AttachmentInfo toAttachmentInfo(Attachment attachment) {
        return new AttachmentInfo(
                attachment.getId(),
                attachment.getOriginalFileName(),
                attachment.getContentType(),
                attachment.getFileSize()
        );
    }

    private void validateContentSize(String content) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("Message content must not be empty");
        }

        int byteLength = content.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException(
                    "Message content exceeds maximum size of %d bytes (was %d bytes)"
                            .formatted(MAX_CONTENT_BYTES, byteLength));
        }
    }

    private void checkDirectChatBan(Room room, User sender) {
        List<RoomMemberDto> members = roomMemberService.listMembers(room);

        // Skip ban check for self-DM (Saved Messages) rooms
        long distinctUserCount = members.stream()
                .map(RoomMemberDto::userId)
                .distinct()
                .count();
        if (distinctUserCount <= 1) {
            return;
        }

        for (RoomMemberDto member : members) {
            if (!member.userId().equals(sender.getId())) {
                User otherUser = userRepository.findById(member.userId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "User not found: %s".formatted(member.userId())));
                if (userBanService.isBanExistsBetween(sender, otherUser)) {
                    throw new ForbiddenException("Cannot send messages in this direct chat due to a user ban");
                }
            }
        }
    }
}
