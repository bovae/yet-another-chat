package com.bovae.yac.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.dto.RoomMemberMapper;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class RoomMemberMapperTest {

    private final RoomMemberMapper mapper = Mappers.getMapper(RoomMemberMapper.class);

    @Test
    void toDto_mapsAllFields() {
        UUID userId = UUID.randomUUID();
        Instant joinedAt = Instant.now();

        User user = User.builder()
                .id(userId)
                .username("alice")
                .displayName("Alice Wonderland")
                .email("alice@example.com")
                .passwordHash("hashed")
                .build();

        RoomMember member = RoomMember.builder()
                .user(user)
                .role(RoomRole.ADMIN)
                .joinedAt(joinedAt)
                .build();

        RoomMemberDto dto = mapper.toDto(member);

        assertNotNull(dto);
        assertEquals(userId, dto.userId());
        assertEquals("alice", dto.username());
        assertEquals("Alice Wonderland", dto.displayName());
        assertEquals(RoomRole.ADMIN, dto.role());
        assertEquals(joinedAt, dto.joinedAt());
    }

    @Test
    void toDto_nullDisplayName_mapsToNull() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .username("bob")
                .displayName(null)
                .email("bob@example.com")
                .passwordHash("hashed")
                .build();

        RoomMember member = RoomMember.builder()
                .user(user)
                .role(RoomRole.MEMBER)
                .joinedAt(Instant.now())
                .build();

        RoomMemberDto dto = mapper.toDto(member);

        assertNotNull(dto);
        assertNull(dto.displayName());
        assertEquals("bob", dto.username());
    }

    @Test
    void toDto_nullInput_returnsNull() {
        assertNull(mapper.toDto(null));
    }

    @Test
    void toDtoList_mapsAllMembers() {
        User user1 = User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .displayName("Alice")
                .email("alice@example.com")
                .passwordHash("h1")
                .build();

        User user2 = User.builder()
                .id(UUID.randomUUID())
                .username("bob")
                .displayName("Bob")
                .email("bob@example.com")
                .passwordHash("h2")
                .build();

        List<RoomMember> members = List.of(
                RoomMember.builder()
                        .user(user1)
                        .role(RoomRole.OWNER)
                        .joinedAt(Instant.now())
                        .build(),
                RoomMember.builder()
                        .user(user2)
                        .role(RoomRole.MEMBER)
                        .joinedAt(Instant.now())
                        .build());

        List<RoomMemberDto> dtos = mapper.toDtoList(members);

        assertEquals(2, dtos.size());
        assertEquals("alice", dtos.get(0).username());
        assertEquals("bob", dtos.get(1).username());
    }

    @Test
    void toDtoList_emptyList_returnsEmptyList() {
        List<RoomMemberDto> dtos = mapper.toDtoList(List.of());

        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }
}
