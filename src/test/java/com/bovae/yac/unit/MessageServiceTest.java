package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
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
import com.bovae.yac.service.FileStorageService;
import com.bovae.yac.service.FriendshipService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.UserBanService;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/**
 * Unit tests for {@link MessageService}.
 *
 * <p>Validates Correctness Properties: CP 17, CP 18, CP 19, CP 22, CP 23.
 * <p>Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7.
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMemberService roomMemberService;

    @Mock
    private UserBanService userBanService;

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private RoomBanRepository roomBanRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MessageService messageService;

    private User sender;
    private User otherUser;
    private Room room;

    @BeforeEach
    void setUp() {
        sender = User.builder()
                .id(UUID.randomUUID())
                .email("sender@test.com")
                .username("sender")
                .passwordHash("$2a$10$hash")
                .build();

        otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@test.com")
                .username("other")
                .passwordHash("$2a$10$hash")
                .build();

        room = Room.builder()
                .id(UUID.randomUUID())
                .name("test-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(sender)
                .nextWatermark(5L)
                .build();
    }

    /**
     * Validates CP 22: sendMessage persists a message with the correct watermark
     * and increments the room watermark.
     */
    @Test
    void sendMessage_persistsMessageWithCorrectWatermarkAndIncrementsRoomWatermark() {
        when(roomMemberService.isMember(room, sender)).thenReturn(true);
        // Atomic reservation: incrementWatermark bumps 5 -> 6, nextWatermarkOf returns 6,
        // and the allocated watermark is that value minus one (5).
        when(roomRepository.nextWatermarkOf(room.getId())).thenReturn(6L);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message m = invocation.getArgument(0);
            m.setId(UUID.randomUUID());
            m.setCreatedAt(Instant.now());
            return m;
        });

        long expectedWatermark = room.getNextWatermark(); // 5

        Message result = messageService.sendMessage(room, sender, "Hello world", null);

        assertThat(result.getWatermark()).isEqualTo(expectedWatermark);
        assertThat(result.getContent()).isEqualTo("Hello world");
        assertThat(result.getSender()).isEqualTo(sender);
        assertThat(result.getRoom()).isEqualTo(room);
        assertThat(result.isEdited()).isFalse();

        // Watermark is reserved via the atomic native increment, not by mutating the entity.
        verify(roomRepository).incrementWatermark(room.getId());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getWatermark()).isEqualTo(expectedWatermark);
    }

    /**
     * Validates CP 17: sendMessage rejects content exceeding 3072 UTF-8 bytes.
     */
    @Test
    void sendMessage_withContentExceeding3072Bytes_throwsIllegalArgumentException() {
        when(roomMemberService.isMember(room, sender)).thenReturn(true);

        // Create a string that exceeds 3072 UTF-8 bytes using multi-byte characters
        // Each '€' is 3 UTF-8 bytes, so 1025 of them = 3075 bytes > 3072
        String oversizedContent = "€".repeat(1025);

        assertThatThrownBy(() -> messageService.sendMessage(room, sender, oversizedContent, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum size");

        verify(messageRepository, never()).save(any());
    }

    /**
     * Validates CP 17 boundary: content of exactly {@code MAX_CONTENT_BYTES} (3072) bytes is
     * accepted — the size limit is inclusive, so the check rejects only strictly larger content.
     */
    @Test
    void sendMessage_withContentExactlyAt3072Bytes_persistsMessage() {
        when(roomMemberService.isMember(room, sender)).thenReturn(true);
        when(roomRepository.nextWatermarkOf(room.getId())).thenReturn(6L);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Exactly 3072 ASCII bytes — the inclusive boundary that must NOT be rejected.
        String maxContent = "a".repeat(3072);

        Message result = messageService.sendMessage(room, sender, maxContent, null);

        assertThat(result.getContent()).isEqualTo(maxContent);
        verify(messageRepository).save(any(Message.class));
    }

    /**
     * Validates CP 17: sendMessage rejects a non-member sender.
     */
    @Test
    void sendMessage_byNonMember_throwsForbiddenException() {
        when(roomMemberService.isMember(room, otherUser)).thenReturn(false);

        assertThatThrownBy(() -> messageService.sendMessage(room, otherUser, "Hello", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not a member");

        verify(messageRepository, never()).save(any());
        verify(roomRepository, never()).save(any());
    }

    /**
     * Validates CP 18: editMessage updates content and sets the edited flag to true.
     */
    @Test
    void editMessage_byAuthor_updatesContentAndSetsEditedFlag() {
        UUID messageId = UUID.randomUUID();
        Message existingMessage = Message.builder()
                .id(messageId)
                .room(room)
                .sender(sender)
                .content("Original content")
                .edited(false)
                .watermark(3L)
                .build();

        when(messageRepository.findByIdWithSenderAndReplyTo(messageId)).thenReturn(Optional.of(existingMessage));
        // Editing enforces the same send-time access guard (R1-27).
        when(roomMemberService.isMember(room, sender)).thenReturn(true);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message result = messageService.editMessage(messageId, sender, "Updated content");

        assertThat(result.getContent()).isEqualTo("Updated content");
        assertThat(result.isEdited()).isTrue();

        verify(messageRepository).save(existingMessage);
    }

    /**
     * Validates CP 18: editMessage by a non-author throws ForbiddenException.
     */
    @Test
    void editMessage_byNonAuthor_throwsForbiddenException() {
        UUID messageId = UUID.randomUUID();
        Message existingMessage = Message.builder()
                .id(messageId)
                .room(room)
                .sender(sender)
                .content("Original content")
                .edited(false)
                .watermark(3L)
                .build();

        when(messageRepository.findByIdWithSenderAndReplyTo(messageId)).thenReturn(Optional.of(existingMessage));

        assertThatThrownBy(() -> messageService.editMessage(messageId, otherUser, "Hacked content"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the author");

        verify(messageRepository, never()).save(any());
    }

    /**
     * Validates CP 19: deleteMessage by the author permanently removes the message.
     */
    @Test
    void deleteMessage_byAuthor_removesMessage() {
        UUID messageId = UUID.randomUUID();
        Message existingMessage = Message.builder()
                .id(messageId)
                .room(room)
                .sender(sender)
                .content("To be deleted")
                .edited(false)
                .watermark(3L)
                .build();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(existingMessage));

        messageService.deleteMessage(messageId, sender, room);

        verify(messageRepository).delete(existingMessage);
    }

    /**
     * Validates CP 23: getMessageHistory is backward pagination — it returns the newest page
     * (before == null), rendered oldest-first, with hasMore meaning older messages still exist
     * and nextCursor being the oldest returned watermark (the next {@code before}).
     */
    @Test
    void getMessageHistory_returnsPaginatedMessagesOrderedByWatermark() {
        int pageSize = 2;

        Message msg1 = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("First")
                .edited(false)
                .watermark(1L)
                .build();
        msg1.setCreatedAt(Instant.now().minusSeconds(30));

        Message msg2 = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("Second")
                .edited(false)
                .watermark(2L)
                .build();
        msg2.setCreatedAt(Instant.now().minusSeconds(20));

        // Third message indicates there are more results
        Message msg3 = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("Third")
                .edited(false)
                .watermark(3L)
                .build();
        msg3.setCreatedAt(Instant.now().minusSeconds(10));

        // Backward query returns newest-first; size+1 rows signal that older messages remain.
        when(messageRepository.findByRoomAndWatermarkLessThanWithFetches(
                        eq(room), eq(Long.MAX_VALUE), any(PageRequest.class)))
                .thenReturn(List.of(msg3, msg2, msg1));

        MessagePage page = messageService.getMessageHistory(room, null, pageSize);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.messages()).hasSize(pageSize);
        // nextCursor is the oldest returned watermark — the `before` cursor for the next page.
        assertThat(page.nextCursor()).isEqualTo(2L);
        // Rendered oldest-first.
        assertThat(page.messages().get(0).watermark()).isEqualTo(2L);
        assertThat(page.messages().get(1).watermark()).isEqualTo(3L);

        // One extra row (size + 1) is requested so the surplus row can flag hasMore.
        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(messageRepository)
                .findByRoomAndWatermarkLessThanWithFetches(eq(room), eq(Long.MAX_VALUE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(pageSize + 1);
    }

    /**
     * Validates CP 23 boundary: when exactly {@code size} rows come back (no surplus row),
     * hasMore is false and all rows are returned — the strict {@code >} comparison must not
     * treat a full-but-not-overflowing page as "more available".
     */
    @Test
    void getMessageHistory_hasMoreIsFalse_whenExactlySizeMessagesReturned() {
        int pageSize = 2;

        Message msg1 = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("First")
                .watermark(1L)
                .build();
        Message msg2 = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("Second")
                .watermark(2L)
                .build();

        // Exactly `size` rows, no surplus row, so no older messages remain.
        when(messageRepository.findByRoomAndWatermarkLessThanWithFetches(
                        eq(room), eq(Long.MAX_VALUE), any(PageRequest.class)))
                .thenReturn(List.of(msg2, msg1));

        MessagePage page = messageService.getMessageHistory(room, null, pageSize);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.messages()).hasSize(pageSize);
    }

    // --- sendMessage: reply, content, and access guards ---

    @Test
    void sendMessage_withReplyTo_preservesOriginalReplyToId() {
        when(roomMemberService.isMember(room, sender)).thenReturn(true);
        when(roomRepository.nextWatermarkOf(room.getId())).thenReturn(6L);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message replyTo = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("Parent")
                .watermark(1L)
                .build();

        Message result = messageService.sendMessage(room, sender, "Reply", replyTo);

        assertThat(result.getReplyTo()).isEqualTo(replyTo);
        assertThat(result.getOriginalReplyToId()).isEqualTo(replyTo.getId());
    }

    @ParameterizedTest(name = "content=[{0}]")
    @NullAndEmptySource
    void sendMessage_withNullOrEmptyContent_throwsIllegalArgumentException(String content) {
        when(roomMemberService.isMember(room, sender)).thenReturn(true);

        assertThatThrownBy(() -> messageService.sendMessage(room, sender, content, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");

        verify(messageRepository, never()).save(any());
    }

    @Test
    void sendMessage_whenRoomBanned_throwsForbiddenException() {
        when(roomMemberService.isMember(room, sender)).thenReturn(true);
        when(roomBanRepository.existsByRoomAndUser(room, sender)).thenReturn(true);

        assertThatThrownBy(() -> messageService.sendMessage(room, sender, "Hello", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("banned from this room");

        verify(messageRepository, never()).save(any());
    }

    // --- sendMessage in a DIRECT room: friendship / ban checks ---

    @Test
    void sendMessage_inSelfDirectRoom_skipsFriendAndBanChecks() {
        Room directRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("saved-messages")
                .visibility(RoomVisibility.DIRECT)
                .owner(sender)
                .nextWatermark(1L)
                .build();

        when(roomMemberService.isMember(directRoom, sender)).thenReturn(true);
        // Self-DM: only one distinct participant, so friend/ban checks are skipped entirely.
        when(roomMemberService.listMembers(directRoom))
                .thenReturn(List.of(new RoomMemberDto(sender.getId(), "sender", null, RoomRole.OWNER, Instant.now())));
        when(roomRepository.nextWatermarkOf(directRoom.getId())).thenReturn(2L);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message result = messageService.sendMessage(directRoom, sender, "Note to self", null);

        assertThat(result.getContent()).isEqualTo("Note to self");
        verify(messageRepository).save(any(Message.class));
        verify(userRepository, never()).findById(any());
        verify(userBanService, never()).isBanExistsBetween(any(), any());
        verify(friendshipService, never()).areFriends(any(), any());
    }

    @Test
    void sendMessage_inDirectRoom_whenOtherParticipantMissing_throwsResourceNotFoundException() {
        Room directRoom = directRoomWithSenderAndOther();

        when(roomMemberService.isMember(directRoom, sender)).thenReturn(true);
        when(roomMemberService.listMembers(directRoom)).thenReturn(directMembers());
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.sendMessage(directRoom, sender, "Hi", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found");

        verify(messageRepository, never()).save(any());
    }

    @Test
    void sendMessage_inDirectRoom_whenNotFriends_throwsForbiddenException() {
        Room directRoom = directRoomWithSenderAndOther();

        when(roomMemberService.isMember(directRoom, sender)).thenReturn(true);
        when(roomMemberService.listMembers(directRoom)).thenReturn(directMembers());
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        when(userBanService.isBanExistsBetween(sender, otherUser)).thenReturn(false);
        when(friendshipService.areFriends(sender, otherUser)).thenReturn(false);

        assertThatThrownBy(() -> messageService.sendMessage(directRoom, sender, "Hi", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("must be friends");

        verify(messageRepository, never()).save(any());
    }

    // --- editMessage: not-found and null-sender guards ---

    @Test
    void editMessage_whenMessageNotFound_throwsResourceNotFoundException() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findByIdWithSenderAndReplyTo(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.editMessage(messageId, sender, "New content"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Message not found");

        verify(messageRepository, never()).save(any());
    }

    @Test
    void editMessage_whenSenderIsNull_throwsForbiddenException() {
        UUID messageId = UUID.randomUUID();
        Message orphanMessage = Message.builder()
                .id(messageId)
                .room(room)
                .sender(null)
                .content("Original")
                .watermark(3L)
                .build();

        when(messageRepository.findByIdWithSenderAndReplyTo(messageId)).thenReturn(Optional.of(orphanMessage));

        assertThatThrownBy(() -> messageService.editMessage(messageId, sender, "New content"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the author");

        verify(messageRepository, never()).save(any());
    }

    /**
     * Validates CP 17/R1-27: editing enforces the same content-size guard as sending, so
     * oversized new content is rejected and the message is never persisted.
     */
    @Test
    void editMessage_withContentExceeding3072Bytes_throwsIllegalArgumentException() {
        UUID messageId = UUID.randomUUID();
        Message existingMessage = Message.builder()
                .id(messageId)
                .room(room)
                .sender(sender)
                .content("Original content")
                .edited(false)
                .watermark(3L)
                .build();

        when(messageRepository.findByIdWithSenderAndReplyTo(messageId)).thenReturn(Optional.of(existingMessage));
        when(roomMemberService.isMember(room, sender)).thenReturn(true);

        String oversizedContent = "a".repeat(3073);

        assertThatThrownBy(() -> messageService.editMessage(messageId, sender, oversizedContent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum size");

        verify(messageRepository, never()).save(any());
    }

    // --- deleteMessage: room mismatch, membership, role, and attachment cleanup ---

    @Test
    void deleteMessage_whenMessageBelongsToDifferentRoom_throwsResourceNotFoundException() {
        UUID messageId = UUID.randomUUID();
        Room otherRoom = Room.builder()
                .id(UUID.randomUUID())
                .name("other-room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(sender)
                .nextWatermark(1L)
                .build();
        Message message = Message.builder()
                .id(messageId)
                .room(otherRoom)
                .sender(sender)
                .content("Elsewhere")
                .watermark(1L)
                .build();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> messageService.deleteMessage(messageId, sender, room))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found in this room");

        verify(messageRepository, never()).delete(any());
    }

    @Test
    void deleteMessage_byNonMember_throwsForbiddenException() {
        UUID messageId = UUID.randomUUID();
        Message message = Message.builder()
                .id(messageId)
                .room(room)
                .sender(sender)
                .content("Someone else's")
                .watermark(1L)
                .build();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), otherUser.getId())))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.deleteMessage(messageId, otherUser, room))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not a member");

        verify(messageRepository, never()).delete(any());
    }

    @Test
    void deleteMessage_byNonAuthorMemberWithoutModeratorRole_throwsForbiddenException() {
        UUID messageId = UUID.randomUUID();
        Message message = Message.builder()
                .id(messageId)
                .room(room)
                .sender(sender)
                .content("Someone else's")
                .watermark(1L)
                .build();
        RoomMember plainMember = RoomMember.builder()
                .room(room)
                .user(otherUser)
                .role(RoomRole.MEMBER)
                .build();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), otherUser.getId())))
                .thenReturn(Optional.of(plainMember));

        assertThatThrownBy(() -> messageService.deleteMessage(messageId, otherUser, room))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("author or an admin");

        verify(messageRepository, never()).delete(any());
    }

    @Test
    void deleteMessage_byNonAuthorAdmin_removesMessage() {
        UUID messageId = UUID.randomUUID();
        // Orphaned message (sender == null) so the author short-circuit is skipped and
        // membership/role is checked instead.
        Message message = Message.builder()
                .id(messageId)
                .room(room)
                .sender(null)
                .content("Orphaned")
                .watermark(1L)
                .build();
        RoomMember admin = RoomMember.builder()
                .room(room)
                .user(otherUser)
                .role(RoomRole.ADMIN)
                .build();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(roomMemberRepository.findById(new RoomMemberId(room.getId(), otherUser.getId())))
                .thenReturn(Optional.of(admin));

        messageService.deleteMessage(messageId, otherUser, room);

        verify(messageRepository).delete(message);
    }

    @Test
    void deleteMessage_withAttachments_schedulesFileDeletionAfterCommit() {
        UUID messageId = UUID.randomUUID();
        Message message = Message.builder()
                .id(messageId)
                .room(room)
                .sender(sender)
                .content("Has attachment")
                .watermark(1L)
                .build();
        Attachment attachment = Attachment.builder()
                .id(UUID.randomUUID())
                .message(message)
                .originalFileName("photo.png")
                .storagePath("uploads/photo.png")
                .fileSize(1024L)
                .contentType("image/png")
                .build();

        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(attachmentRepository.findByMessageId(messageId)).thenReturn(List.of(attachment));

        messageService.deleteMessage(messageId, sender, room);

        verify(messageRepository).delete(message);
        verify(fileStorageService).deleteFilesAfterCommit(List.of(Paths.get("uploads/photo.png")));
    }

    // --- getMessageHistory: batch attachment loading ---

    @Test
    void getMessageHistory_attachesBatchLoadedAttachmentsToResponses() {
        Message message = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("With file")
                .watermark(1L)
                .build();
        message.setCreatedAt(Instant.now());
        Attachment attachment = Attachment.builder()
                .id(UUID.randomUUID())
                .message(message)
                .originalFileName("doc.pdf")
                .storagePath("uploads/doc.pdf")
                .fileSize(2048L)
                .contentType("application/pdf")
                .build();

        when(messageRepository.findByRoomAndWatermarkLessThanWithFetches(
                        eq(room), eq(Long.MAX_VALUE), any(PageRequest.class)))
                .thenReturn(List.of(message));
        when(attachmentRepository.findByMessageIdIn(List.of(message.getId()))).thenReturn(List.of(attachment));

        MessagePage page = messageService.getMessageHistory(room, null, 10);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.messages()).hasSize(1);
        assertThat(page.messages().get(0).attachments()).hasSize(1);
        assertThat(page.messages().get(0).attachments().get(0).originalFileName())
                .isEqualTo("doc.pdf");
    }

    // --- getMessageResponse: reply metadata and deleted-user rendering ---

    @Test
    void getMessageResponse_whenMessageNotFound_throwsResourceNotFoundException() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findByIdWithSenderAndReplyTo(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.getMessageResponse(messageId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Message not found");
    }

    @Test
    void getMessageResponse_withReplyTo_includesReplySenderAndSnippet() {
        Message replyTo = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("Short parent")
                .watermark(1L)
                .build();
        Message message = messageWithReplyTo(replyTo, null);

        when(messageRepository.findByIdWithSenderAndReplyTo(message.getId())).thenReturn(Optional.of(message));

        ChatMessageResponse response = messageService.getMessageResponse(message.getId());

        assertThat(response.replyToId()).isEqualTo(replyTo.getId());
        assertThat(response.replyToSenderUsername()).isEqualTo("sender");
        assertThat(response.replyToContentSnippet()).isEqualTo("Short parent");
    }

    @Test
    void getMessageResponse_withDeletedReplySenderAndLongContent_usesDeletedUserAndTruncatesSnippet() {
        String longContent = "x".repeat(150);
        Message replyTo = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(null)
                .content(longContent)
                .watermark(1L)
                .build();
        Message message = messageWithReplyTo(replyTo, null);

        when(messageRepository.findByIdWithSenderAndReplyTo(message.getId())).thenReturn(Optional.of(message));

        ChatMessageResponse response = messageService.getMessageResponse(message.getId());

        assertThat(response.replyToSenderUsername()).isEqualTo("Deleted user");
        assertThat(response.replyToContentSnippet()).hasSize(100);
    }

    @Test
    void getMessageResponse_withDeletedReplyTarget_fallsBackToOriginalReplyToId() {
        UUID originalReplyToId = UUID.randomUUID();
        Message message = messageWithReplyTo(null, originalReplyToId);

        when(messageRepository.findByIdWithSenderAndReplyTo(message.getId())).thenReturn(Optional.of(message));

        ChatMessageResponse response = messageService.getMessageResponse(message.getId());

        assertThat(response.replyToId()).isEqualTo(originalReplyToId);
        assertThat(response.replyToSenderUsername()).isNull();
        assertThat(response.replyToContentSnippet()).isNull();
    }

    @Test
    void getMessageResponse_withNullSender_rendersDeletedUser() {
        Message message = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(null)
                .content("Orphaned message")
                .watermark(1L)
                .build();
        message.setCreatedAt(Instant.now());

        when(messageRepository.findByIdWithSenderAndReplyTo(message.getId())).thenReturn(Optional.of(message));

        ChatMessageResponse response = messageService.getMessageResponse(message.getId());

        assertThat(response.senderId()).isNull();
        assertThat(response.senderUsername()).isEqualTo("Deleted user");
        assertThat(response.senderDisplayName()).isNull();
    }

    // --- helpers ---

    private Room directRoomWithSenderAndOther() {
        return Room.builder()
                .id(UUID.randomUUID())
                .name("dm")
                .visibility(RoomVisibility.DIRECT)
                .owner(sender)
                .nextWatermark(1L)
                .build();
    }

    private List<RoomMemberDto> directMembers() {
        return List.of(
                new RoomMemberDto(sender.getId(), "sender", null, RoomRole.MEMBER, Instant.now()),
                new RoomMemberDto(otherUser.getId(), "other", null, RoomRole.MEMBER, Instant.now()));
    }

    private Message messageWithReplyTo(Message replyTo, UUID originalReplyToId) {
        Message message = Message.builder()
                .id(UUID.randomUUID())
                .room(room)
                .sender(sender)
                .content("Child message")
                .replyTo(replyTo)
                .originalReplyToId(originalReplyToId)
                .watermark(2L)
                .build();
        message.setCreatedAt(Instant.now());
        return message;
    }
}
