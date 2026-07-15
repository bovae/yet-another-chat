package com.bovae.yac.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.dto.UserMapper;
import com.bovae.yac.model.entity.User;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class UserMapperTest {

    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    @Test
    void toDto_mapsAllFields() {
        UUID userId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        User user = User.builder()
                .id(userId)
                .email("alice@example.com")
                .username("alice")
                .displayName("Alice Wonderland")
                .passwordHash("super_secret_hash")
                .build();
        user.setCreatedAt(createdAt);

        UserDto dto = mapper.toDto(user);

        assertNotNull(dto);
        assertEquals(userId, dto.id());
        assertEquals("alice@example.com", dto.email());
        assertEquals("alice", dto.username());
        assertEquals("Alice Wonderland", dto.displayName());
        assertEquals(createdAt, dto.createdAt());
    }

    @Test
    void toDto_nullDisplayName_mapsToNull() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("bob@example.com")
                .username("bob")
                .displayName(null)
                .passwordHash("hashed")
                .build();

        UserDto dto = mapper.toDto(user);

        assertNotNull(dto);
        assertNull(dto.displayName());
    }

    @Test
    void toDto_nullInput_returnsNull() {
        assertNull(mapper.toDto(null));
    }

    @Test
    void userDto_doesNotContainPasswordHash() {
        RecordComponent[] components = UserDto.class.getRecordComponents();
        boolean hasPasswordHash = Arrays.stream(components).anyMatch(c -> "passwordHash".equals(c.getName()));

        assertTrue(!hasPasswordHash, "UserDto must NOT contain a passwordHash field");
    }
}
