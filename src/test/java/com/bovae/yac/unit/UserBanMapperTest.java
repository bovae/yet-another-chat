package com.bovae.yac.unit;

import com.bovae.yac.model.dto.UserBanDto;
import com.bovae.yac.model.dto.UserBanMapper;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserBanMapperTest {

    private final UserBanMapper mapper = Mappers.getMapper(UserBanMapper.class);

    @Test
    void toDto_mapsBlockedUserFields() {
        UUID banId = UUID.randomUUID();
        UUID blockedId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        User blocked = User.builder()
                .id(blockedId)
                .username("troll")
                .displayName("Troll User")
                .email("troll@example.com")
                .passwordHash("hashed")
                .build();

        User blocker = User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .displayName("Alice")
                .email("alice@example.com")
                .passwordHash("hashed")
                .build();

        UserBan ban = UserBan.builder()
                .id(banId)
                .blocker(blocker)
                .blocked(blocked)
                .createdAt(createdAt)
                .build();

        UserBanDto dto = mapper.toDto(ban);

        assertNotNull(dto);
        assertEquals(banId, dto.id());
        assertEquals(blockedId, dto.blockedId());
        assertEquals("troll", dto.blockedUsername());
        assertEquals("Troll User", dto.blockedDisplayName());
        assertEquals(createdAt, dto.createdAt());
    }

    @Test
    void toDto_nullBlockedDisplayName_mapsToNull() {
        User blocked = User.builder()
                .id(UUID.randomUUID())
                .username("anon")
                .displayName(null)
                .email("anon@example.com")
                .passwordHash("hashed")
                .build();

        UserBan ban = UserBan.builder()
                .id(UUID.randomUUID())
                .blocker(User.builder().id(UUID.randomUUID()).username("x").email("x@e.com").passwordHash("h").build())
                .blocked(blocked)
                .createdAt(Instant.now())
                .build();

        UserBanDto dto = mapper.toDto(ban);

        assertNotNull(dto);
        assertNull(dto.blockedDisplayName());
        assertEquals("anon", dto.blockedUsername());
    }

    @Test
    void toDto_nullInput_returnsNull() {
        assertNull(mapper.toDto(null));
    }

    @Test
    void toDtoList_mapsAllBans() {
        User blocker = User.builder().id(UUID.randomUUID()).username("alice").email("a@e.com").passwordHash("h").build();
        User blocked1 = User.builder().id(UUID.randomUUID()).username("troll1").displayName("Troll 1")
                .email("t1@e.com").passwordHash("h").build();
        User blocked2 = User.builder().id(UUID.randomUUID()).username("troll2").displayName("Troll 2")
                .email("t2@e.com").passwordHash("h").build();

        List<UserBan> bans = List.of(
                UserBan.builder().id(UUID.randomUUID()).blocker(blocker).blocked(blocked1).createdAt(Instant.now()).build(),
                UserBan.builder().id(UUID.randomUUID()).blocker(blocker).blocked(blocked2).createdAt(Instant.now()).build()
        );

        List<UserBanDto> dtos = mapper.toDtoList(bans);

        assertEquals(2, dtos.size());
        assertEquals("troll1", dtos.get(0).blockedUsername());
        assertEquals("troll2", dtos.get(1).blockedUsername());
    }

    @Test
    void toDtoList_emptyList_returnsEmptyList() {
        List<UserBanDto> dtos = mapper.toDtoList(List.of());

        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }
}
