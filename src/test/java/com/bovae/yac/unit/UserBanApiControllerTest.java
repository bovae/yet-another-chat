package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.UserBanApiController;
import com.bovae.yac.controller.api.UserBanApiController.BanUserRequest;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.UserBanDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.repository.UserBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.UserBanService;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link UserBanApiController}. */
@ExtendWith(MockitoExtension.class)
class UserBanApiControllerTest {

    @Mock
    private UserBanService userBanService;

    @Mock
    private UserBanRepository userBanRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private UserBanApiController controller;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("caller@test.com")
                .username("caller")
                .displayName("Caller")
                .build();

        lenient().when(principal.getName()).thenReturn(user.getEmail());
        lenient().when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test
    void listBans_shouldReturnBans_whenResolved() {
        List<UserBanDto> bans = List.of();
        when(userBanService.listBannedUsers(user)).thenReturn(bans);

        ResponseEntity<List<UserBanDto>> response = controller.listBans(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(bans);
    }

    @Test
    void banUser_shouldReturnCreatedAndDelegate_whenBothUsersResolved() {
        User blocked = User.builder().id(UUID.randomUUID()).username("bob").build();
        BanUserRequest request = new BanUserRequest(blocked.getId());
        when(userRepository.findById(blocked.getId())).thenReturn(Optional.of(blocked));

        ResponseEntity<Void> response = controller.banUser(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(userBanService).banUser(user, blocked);
    }

    @Test
    void banUser_shouldThrowNotFound_whenTargetUserMissing() {
        UUID targetId = UUID.randomUUID();
        BanUserRequest request = new BanUserRequest(targetId);
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.banUser(request, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(targetId.toString());

        verify(userBanService, never()).banUser(user, null);
    }

    @Test
    void unbanUser_shouldReturnNoContentAndDelegate_whenBanFound() {
        UUID banId = UUID.randomUUID();
        User blocked = User.builder().id(UUID.randomUUID()).username("bob").build();
        UserBan ban = UserBan.builder().id(banId).blocker(user).blocked(blocked).build();
        when(userBanRepository.findById(banId)).thenReturn(Optional.of(ban));

        ResponseEntity<Void> response = controller.unbanUser(banId, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(userBanService).unbanUser(user, blocked);
    }

    @Test
    void unbanUser_shouldThrowNotFound_whenBanMissing() {
        UUID banId = UUID.randomUUID();
        when(userBanRepository.findById(banId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.unbanUser(banId, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(banId.toString());

        verify(userBanService, never()).unbanUser(any(), any());
    }

    @Test
    void listBans_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listBans(principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(userBanService, never()).listBannedUsers(user);
    }
}
