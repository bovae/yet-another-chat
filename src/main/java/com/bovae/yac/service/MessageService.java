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
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    private static final String DELETED_USER = "Deleted user";

    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomMemberService roomMemberService;
    private final UserBanService userBanService;
    private final FriendshipService friendshipService;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final AttachmentRepository attachmentRepository;
    private final RoomBanRepository roomBanRepository;
    private final FileStorageService fileStorageService;

    @Transactional
    public Message sendMessage(Room room, User sender, String content, Message replyTo) {
        assertCanPost(room, sender);
        validateContentSize(content);

        // Atomic watermark reservation: the UPDATE's row lock serializes concurrent
        // senders so watermarks are gap-free and unique (R1-07).
        roomRepository.incrementWatermark(room.getId());
        Long watermark = roomRepository.nextWatermarkOf(room.getId()) - 1;

        Message message = Message.builder()
                .room(room)
                .sender(sender)
                .content(content)
                .replyTo(replyTo)
                .originalReplyToId(replyTo != null ? replyTo.getId() : null)
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
        Message message = messageRepository.findByIdWithSenderAndReplyTo(messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message not found: %s".formatted(messageId)));

        if (message.getSender() == null || !message.getSender().getId().equals(author.getId())) {
            throw new ForbiddenException("Only the author can edit this message");
        }

        // Editing enforces the same access guards as sending (R1-27).
        assertCanPost(message.getRoom(), author);
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

        // The message must belong to the room named in the URL, before role checks (R1-25).
        if (!message.getRoom().getId().equals(room.getId())) {
            throw new ResourceNotFoundException("Message not found in this room: %s".formatted(messageId));
        }

        boolean isAuthor = message.getSender() != null
                && message.getSender().getId().equals(requestingUser.getId());

        if (!isAuthor) {
            RoomMember member = roomMemberRepository.findById(
                    new RoomMemberId(room.getId(), requestingUser.getId()))
                    .orElseThrow(() -> new ForbiddenException("User is not a member of this room"));

            if (member.getRole() != RoomRole.ADMIN && member.getRole() != RoomRole.OWNER) {
                throw new ForbiddenException("Only the author or an admin can delete this message");
            }
        }

        // Capture attachment file paths before the cascade removes their rows, then delete
        // them from disk once the transaction commits (R1-16).
        List<Path> attachmentFiles = attachmentRepository.findByMessageId(messageId).stream()
                .map(a -> Paths.get(a.getStoragePath()))
                .toList();

        messageRepository.delete(message);
        fileStorageService.deleteFilesAfterCommit(attachmentFiles);

        LOG.info("Message deleted: messageId={}, deletedBy={}", messageId, requestingUser.getId());
    }

    /**
     * Backward pagination for the room view (R1-01, R1-02). Returns up to {@code size}
     * messages older than {@code before} (newest page when {@code before} is null),
     * rendered oldest-first. {@code hasMore} means older messages still exist and
     * {@code nextCursor} is the oldest returned watermark (the next {@code before}).
     */
    @Transactional(readOnly = true)
    public MessagePage getMessageHistory(Room room, Long before, int size) {
        long effectiveBefore = (before != null) ? before : Long.MAX_VALUE;

        List<Message> descending = messageRepository.findByRoomAndWatermarkLessThanWithFetches(
                room, effectiveBefore, PageRequest.of(0, size + 1));

        boolean hasMore = descending.size() > size;
        if (hasMore) {
            descending = descending.subList(0, size);
        }

        List<Message> ordered = new ArrayList<>(descending);
        Collections.reverse(ordered); // oldest-first for render

        Long nextCursor = ordered.isEmpty() ? null : ordered.getFirst().getWatermark();
        return new MessagePage(toResponses(ordered), nextCursor, hasMore);
    }

    /**
     * Ascending catch-up for reconnect (R1-22). Returns up to {@code size} messages newer
     * than {@code after}, oldest-first; {@code hasMore} means even newer messages remain
     * (client loops with {@code nextCursor} until it clears).
     */
    @Transactional(readOnly = true)
    public MessagePage getMessagesSince(Room room, Long after, int size) {
        long effectiveAfter = (after != null) ? after : 0L;

        List<Message> messages = messageRepository.findByRoomAndWatermarkGreaterThanWithFetches(
                room, effectiveAfter, PageRequest.of(0, size + 1));

        boolean hasMore = messages.size() > size;
        if (hasMore) {
            messages = messages.subList(0, size);
        }

        Long nextCursor = messages.isEmpty() ? null : messages.getLast().getWatermark();
        return new MessagePage(toResponses(messages), nextCursor, hasMore);
    }

    private List<ChatMessageResponse> toResponses(List<Message> messages) {
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

        return messages.stream()
                .map(msg -> toResponse(msg, attachmentsByMessageId.getOrDefault(msg.getId(), List.of())))
                .toList();
    }

    /** Loads a persisted message (with sender, reply-to, attachments) as a broadcast DTO. */
    @Transactional(readOnly = true)
    public ChatMessageResponse getMessageResponse(UUID messageId) {
        Message message = messageRepository.findByIdWithSenderAndReplyTo(messageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Message not found: %s".formatted(messageId)));
        return toResponse(message);
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
        UUID replyToId = null;
        String replyToSenderUsername = null;
        String replyToContentSnippet = null;

        if (replyTo != null) {
            replyToId = replyTo.getId();
            replyToSenderUsername = replyTo.getSender() != null ? replyTo.getSender().getUsername() : DELETED_USER;
            String content = replyTo.getContent();
            replyToContentSnippet = content.length() > 100 ? content.substring(0, 100) : content;
        } else if (message.getOriginalReplyToId() != null) {
            // The original replied-to message was deleted (ON DELETE SET NULL nullified reply_to_id),
            // but we preserved the original reference. This lets the template render "Original message deleted".
            replyToId = message.getOriginalReplyToId();
        }

        User sender = message.getSender();
        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                sender != null ? sender.getId() : null,
                sender != null ? sender.getUsername() : DELETED_USER,
                sender != null ? sender.getDisplayName() : null,
                message.getContent(),
                replyToId,
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
                attachment.getFileSize(),
                attachment.getComment()
        );
    }

    /**
     * Send-time access guard shared by send, edit, and attachment upload (R1-27): the user must
     * be a member, not room-banned, and (for DMs) not on either side of a user ban.
     */
    public void assertCanPost(Room room, User user) {
        if (!roomMemberService.isMember(room, user)) {
            throw new ForbiddenException("User is not a member of this room");
        }
        if (room.getVisibility() != RoomVisibility.DIRECT && roomBanRepository.existsByRoomAndUser(room, user)) {
            throw new ForbiddenException("You are banned from this room");
        }
        if (room.getVisibility() == RoomVisibility.DIRECT) {
            checkDirectChatBan(room, user);
        }
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

        // Skip friendship/ban checks for self-DM (Saved Messages) rooms
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
                // Two-person DMs require the participants to currently be friends (R1-26).
                if (!friendshipService.areFriends(sender, otherUser)) {
                    throw new ForbiddenException("You must be friends to message in this direct chat");
                }
            }
        }
    }
}
