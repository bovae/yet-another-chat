package com.bovae.yac.property;

import com.bovae.yac.controller.api.PresenceApiController;
import com.bovae.yac.model.dto.PresenceStatusEntry;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.service.PresenceService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for Presence batch endpoint correctness.
 *
 * Validates: Requirements 4.1, 4.4, 4.5
 */
class PresenceBatchPropertyTest {

    private PresenceService presenceService;
    private PresenceApiController controller;

    @BeforeTry
    void setUp() {
        presenceService = mock(PresenceService.class);
        controller = new PresenceApiController(presenceService);
    }

    @Provide
    Arbitrary<List<UUID>> uuidLists() {
        return Arbitraries.create(UUID::randomUUID)
                .list()
                .ofMinSize(1)
                .ofMaxSize(20);
    }

    @Provide
    Arbitrary<PresenceStatus> presenceStatuses() {
        return Arbitraries.of(PresenceStatus.ONLINE, PresenceStatus.AFK, PresenceStatus.OFFLINE);
    }

    /**
     * Property 3: Presence batch endpoint correctness — response size matches request size
     *
     * For any set of user IDs passed to GET /api/presence, the response SHALL contain
     * exactly one entry per requested ID.
     *
     * Validates: Requirements 4.1, 4.4, 4.5
     */
    @Property(tries = 20)
    void responseShallContainExactlyOneEntryPerRequestedId(
            @ForAll("uuidLists") List<UUID> userIds,
            @ForAll("presenceStatuses") PresenceStatus status
    ) {
        for (UUID id : userIds) {
            when(presenceService.getUserStatus(id)).thenReturn(status);
        }

        String userIdsParam = userIds.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(","));

        ResponseEntity<List<PresenceStatusEntry>> response = controller.getPresence(userIdsParam);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(userIds.size());

        Set<UUID> returnedIds = response.getBody().stream()
                .map(PresenceStatusEntry::userId)
                .collect(Collectors.toSet());

        assertThat(returnedIds).containsExactlyInAnyOrderElementsOf(userIds);
    }

    /**
     * Property 3: Presence batch endpoint correctness — status matches service
     *
     * Each entry's status field SHALL equal the value returned by
     * PresenceService.getUserStatus(userId) at the time of the request.
     *
     * Validates: Requirements 4.1, 4.4, 4.5
     */
    @Property(tries = 20)
    void eachEntryStatusShallMatchServiceResult(
            @ForAll("uuidListsWithStatuses") List<UuidWithStatus> entries
    ) {
        for (UuidWithStatus entry : entries) {
            when(presenceService.getUserStatus(entry.userId())).thenReturn(entry.status());
        }

        String userIdsParam = entries.stream()
                .map(e -> e.userId().toString())
                .collect(Collectors.joining(","));

        ResponseEntity<List<PresenceStatusEntry>> response = controller.getPresence(userIdsParam);

        assertThat(response.getBody()).isNotNull();

        Map<UUID, PresenceStatus> expectedMap = entries.stream()
                .collect(Collectors.toMap(UuidWithStatus::userId, UuidWithStatus::status, (a, b) -> b));

        for (PresenceStatusEntry result : response.getBody()) {
            assertThat(result.status())
                    .as("Status for user %s should match service result", result.userId())
                    .isEqualTo(expectedMap.get(result.userId()));
        }
    }

    @Provide
    Arbitrary<List<UuidWithStatus>> uuidListsWithStatuses() {
        Arbitrary<UuidWithStatus> single = Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.of(PresenceStatus.ONLINE, PresenceStatus.AFK, PresenceStatus.OFFLINE)
        ).as(UuidWithStatus::new);

        return single.list().ofMinSize(1).ofMaxSize(20);
    }

    record UuidWithStatus(UUID userId, PresenceStatus status) {}
}
