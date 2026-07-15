package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.PresenceApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.PresenceStatusEntry;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import com.bovae.yac.service.PresenceVisibilityService;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link PresenceApiController}. */
@ExtendWith(MockitoExtension.class)
class PresenceApiControllerTest {

    @Mock
    private PresenceService presenceService;

    @Mock
    private PresenceVisibilityService presenceVisibilityService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private PresenceApiController controller;

    private User caller;

    @BeforeEach
    void setUp() {
        caller = User.builder()
                .id(UUID.randomUUID())
                .email("caller@test.com")
                .username("caller")
                .build();

        lenient().when(principal.getName()).thenReturn(caller.getEmail());
        lenient().when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.of(caller));
    }

    @Test
    void getPresence_shouldReturnOnlyVisibleEntriesAndSkipBlankIds_whenValidIdsProvided() {
        UUID visibleId = UUID.randomUUID();
        UUID hiddenId = UUID.randomUUID();
        String userIds = visibleId + ", ," + hiddenId;
        when(presenceVisibilityService.visibleAmong(eq(caller.getId()), any())).thenReturn(Set.of(visibleId));
        when(presenceService.getUserStatus(visibleId)).thenReturn(PresenceStatus.ONLINE);

        ResponseEntity<List<PresenceStatusEntry>> response = controller.getPresence(userIds, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<PresenceStatusEntry> body = response.getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).userId()).isEqualTo(visibleId);
        assertThat(body.get(0).status()).isEqualTo(PresenceStatus.ONLINE);
        verify(presenceService).getUserStatus(visibleId);
    }

    @Test
    void getPresence_shouldNotThrow_whenExactlyMaxIdsRequested() {
        // Boundary: 100 ids is allowed; only >100 is rejected. `>=` would wrongly throw here.
        String userIds = IntStream.range(0, 100)
                .mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));
        when(presenceVisibilityService.visibleAmong(eq(caller.getId()), any())).thenReturn(Set.of());

        ResponseEntity<List<PresenceStatusEntry>> response = controller.getPresence(userIds, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getPresence_shouldThrowIllegalArgument_whenTooManyIdsRequested() {
        String userIds = IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));

        assertThatThrownBy(() -> controller.getPresence(userIds, principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many userIds");
    }

    @Test
    void getPresence_shouldThrowIllegalArgument_whenIdIsMalformedUuid() {
        assertThatThrownBy(() -> controller.getPresence("not-a-uuid", principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed UUID");
    }

    @Test
    void getPresence_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(caller.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getPresence(UUID.randomUUID().toString(), principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(caller.getEmail());
    }
}
