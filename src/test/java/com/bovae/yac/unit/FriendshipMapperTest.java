package com.bovae.yac.unit;

import com.bovae.yac.model.dto.FriendshipDto;
import com.bovae.yac.model.dto.FriendshipMapper;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendshipMapperTest {

    private final FriendshipMapper mapper = Mappers.getMapper(FriendshipMapper.class);

    @Test
    void toDto_mapsAllRequesterAndRecipientFields() {
        UUID friendshipId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        User requester = User.builder()
                .id(requesterId)
                .username("alice")
                .displayName("Alice A")
                .email("alice@example.com")
                .passwordHash("h1")
                .build();

        User recipient = User.builder()
                .id(recipientId)
                .username("bob")
                .displayName("Bob B")
                .email("bob@example.com")
                .passwordHash("h2")
                .build();

        Friendship friendship = Friendship.builder()
                .id(friendshipId)
                .requester(requester)
                .recipient(recipient)
                .status(FriendshipStatus.ACCEPTED)
                .requestText("Let's be friends!")
                .build();
        friendship.setCreatedAt(createdAt);

        FriendshipDto dto = mapper.toDto(friendship);

        assertNotNull(dto);
        assertEquals(friendshipId, dto.id());
        assertEquals(requesterId, dto.requesterId());
        assertEquals("alice", dto.requesterUsername());
        assertEquals("Alice A", dto.requesterDisplayName());
        assertEquals(recipientId, dto.recipientId());
        assertEquals("bob", dto.recipientUsername());
        assertEquals("Bob B", dto.recipientDisplayName());
        assertEquals(FriendshipStatus.ACCEPTED, dto.status());
        assertEquals("Let's be friends!", dto.requestText());
        assertEquals(createdAt, dto.createdAt());
    }

    @Test
    void toDto_nullDisplayNames_mapCorrectly() {
        User requester = User.builder()
                .id(UUID.randomUUID())
                .username("alice")
                .displayName(null)
                .email("alice@example.com")
                .passwordHash("h1")
                .build();

        User recipient = User.builder()
                .id(UUID.randomUUID())
                .username("bob")
                .displayName(null)
                .email("bob@example.com")
                .passwordHash("h2")
                .build();

        Friendship friendship = Friendship.builder()
                .id(UUID.randomUUID())
                .requester(requester)
                .recipient(recipient)
                .status(FriendshipStatus.PENDING)
                .build();

        FriendshipDto dto = mapper.toDto(friendship);

        assertNotNull(dto);
        assertNull(dto.requesterDisplayName());
        assertNull(dto.recipientDisplayName());
    }

    @Test
    void toDto_nullInput_returnsNull() {
        assertNull(mapper.toDto(null));
    }

    @Test
    void toDtoList_mapsAllFriendships() {
        User alice = User.builder().id(UUID.randomUUID()).username("alice").displayName("Alice")
                .email("a@e.com").passwordHash("h").build();
        User bob = User.builder().id(UUID.randomUUID()).username("bob").displayName("Bob")
                .email("b@e.com").passwordHash("h").build();
        User carol = User.builder().id(UUID.randomUUID()).username("carol").displayName("Carol")
                .email("c@e.com").passwordHash("h").build();

        List<Friendship> friendships = List.of(
                Friendship.builder().id(UUID.randomUUID()).requester(alice).recipient(bob)
                        .status(FriendshipStatus.ACCEPTED).build(),
                Friendship.builder().id(UUID.randomUUID()).requester(alice).recipient(carol)
                        .status(FriendshipStatus.PENDING).build()
        );

        List<FriendshipDto> dtos = mapper.toDtoList(friendships);

        assertEquals(2, dtos.size());
        assertEquals("bob", dtos.get(0).recipientUsername());
        assertEquals("carol", dtos.get(1).recipientUsername());
    }

    @Test
    void toDtoList_emptyList_returnsEmptyList() {
        List<FriendshipDto> dtos = mapper.toDtoList(List.of());

        assertNotNull(dtos);
        assertTrue(dtos.isEmpty());
    }
}
