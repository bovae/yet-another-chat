package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.UserApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.UpdateProfileRequest;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.UserService;
import java.security.Principal;
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

/** Unit tests for {@link UserApiController}. */
@ExtendWith(MockitoExtension.class)
class UserApiControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private UserApiController controller;

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
    void searchByUsername_shouldReturnMatch_whenUserFound() {
        UUID foundId = UUID.randomUUID();
        UserDto found = new UserDto(foundId, "bob@test.com", "bob", "Bob", null);
        when(userService.findByUsername("bob")).thenReturn(Optional.of(found));

        ResponseEntity<UserApiController.UserSearchResponse> response = controller.searchByUsername("bob", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserApiController.UserSearchResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(foundId);
        assertThat(body.username()).isEqualTo("bob");
        assertThat(body.displayName()).isEqualTo("Bob");
    }

    @Test
    void searchByUsername_shouldThrowNotFound_whenUsernameUnknown() {
        when(userService.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.searchByUsername("ghost", principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void updateProfile_shouldReturnUpdatedDto_whenRequestValid() {
        UpdateProfileRequest request = new UpdateProfileRequest("New Name");
        UserDto updated = new UserDto(user.getId(), user.getEmail(), user.getUsername(), "New Name", null);
        when(userService.updateProfile(user.getId(), "New Name", user.getUsername()))
                .thenReturn(updated);

        ResponseEntity<UserDto> response = controller.updateProfile(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(updated);
    }

    @Test
    void deleteAccount_shouldReturnNoContentAndDelegate_whenPrincipalResolved() {
        ResponseEntity<Void> response = controller.deleteAccount(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(userService).deleteAccount(user.getId());
    }

    @Test
    void deleteAccount_shouldThrowNotFound_whenPrincipalHasNoUser() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.deleteAccount(principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());
    }
}
