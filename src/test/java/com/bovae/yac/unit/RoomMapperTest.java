package com.bovae.yac.unit;

import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.RoomMapper;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoomMapperTest {

    private final RoomMapper mapper = Mappers.getMapper(RoomMapper.class);

    @Test
    void toDto_mapsAllFieldsIncludingOwner() {
        UUID roomId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        User owner = User.builder()
                .id(ownerId)
                .username("owner_user")
                .displayName("Owner")
                .email("owner@example.com")
                .passwordHash("hashed")
                .build();

        Room room = Room.builder()
                .id(roomId)
                .name("General")
                .description("A general chat room")
                .visibility(RoomVisibility.PUBLIC)
                .owner(owner)
                .nextWatermark(42L)
                .build();
        room.setCreatedAt(createdAt);

        RoomDto dto = mapper.toDto(room);

        assertNotNull(dto);
        assertEquals(roomId, dto.id());
        assertEquals("General", dto.name());
        assertEquals("A general chat room", dto.description());
        assertEquals(RoomVisibility.PUBLIC, dto.visibility());
        assertEquals(ownerId, dto.ownerId());
        assertEquals("owner_user", dto.ownerUsername());
        assertEquals(42L, dto.nextWatermark());
        assertEquals(createdAt, dto.createdAt());
    }

    @Test
    void toDto_nullOwner_mapsOwnerFieldsToNull() {
        Room room = Room.builder()
                .id(UUID.randomUUID())
                .name("Orphan Room")
                .description(null)
                .visibility(RoomVisibility.PRIVATE)
                .owner(null)
                .nextWatermark(1L)
                .build();

        RoomDto dto = mapper.toDto(room);

        assertNotNull(dto);
        assertNull(dto.ownerId());
        assertNull(dto.ownerUsername());
        assertEquals("Orphan Room", dto.name());
    }

    @Test
    void toDto_nullInput_returnsNull() {
        assertNull(mapper.toDto(null));
    }
}
