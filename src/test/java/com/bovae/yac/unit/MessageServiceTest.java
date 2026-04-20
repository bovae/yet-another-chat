package com.bovae.yac.unit;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.UserBanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private RoomMemberRepository roomMemberRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private RoomBanRepository roomBanRepository;

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
        when(roomRepository.save(room)).thenReturn(room);
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message m = invocation.getArgument(0);
            m.setId(UUID.randomUUID());
            m.setCreatedAt(Instant.now());
            return m;
        });

        Long originalWatermark = room.getNextWatermark(); // 5

        Message result = messageService.sendMessage(room, sender, "Hello world", null);

        assertThat(result.getWatermark()).isEqualTo(originalWatermark);
        assertThat(room.getNextWatermark()).isEqualTo(originalWatermark + 1);
        assertThat(result.getContent()).isEqualTo("Hello world");
        assertThat(result.getSender()).isEqualTo(sender);
        assertThat(result.getRoom()).isEqualTo(room);
        assertThat(result.isEdited()).isFalse();

        verify(roomRepository).save(room);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getWatermark()).isEqualTo(originalWatermark);
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
     * Validates CP 23: getMessageHistory returns messages ordered by watermark
     * with correct cursor pagination.
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

        // Return size+1 messages to indicate hasMore=true
        when(messageRepository.findByRoomAndWatermarkGreaterThanWithFetches(
                eq(room), eq(0L), any(PageRequest.class)))
                .thenReturn(List.of(msg1, msg2, msg3));

        MessagePage page = messageService.getMessageHistory(room, null, pageSize);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.messages()).hasSize(pageSize);
        assertThat(page.nextCursor()).isEqualTo(2L);
        assertThat(page.messages().get(0).watermark()).isEqualTo(1L);
        assertThat(page.messages().get(1).watermark()).isEqualTo(2L);
    }
}
