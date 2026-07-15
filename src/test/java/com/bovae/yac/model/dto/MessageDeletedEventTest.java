package com.bovae.yac.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageDeletedEventTest {

    @Test
    void of_setsTypeToMessageDeleted() {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID deletedBy = UUID.randomUUID();

        MessageDeletedEvent event = MessageDeletedEvent.of(messageId, roomId, deletedBy);

        assertEquals("MESSAGE_DELETED", event.type());
    }

    @Test
    void of_setsAllUuidFieldsCorrectly() {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID deletedBy = UUID.randomUUID();

        MessageDeletedEvent event = MessageDeletedEvent.of(messageId, roomId, deletedBy);

        assertEquals(messageId, event.messageId());
        assertEquals(roomId, event.roomId());
        assertEquals(deletedBy, event.deletedBy());
    }
}
