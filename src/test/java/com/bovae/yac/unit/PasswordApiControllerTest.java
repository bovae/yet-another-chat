package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.api.PasswordApiController;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.PasswordChangeRequest;
import com.bovae.yac.model.dto.PasswordResetConfirmRequest;
import com.bovae.yac.model.dto.PasswordResetRequest;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PasswordService;
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

/** Unit tests for {@link PasswordApiController}. */
@ExtendWith(MockitoExtension.class)
class PasswordApiControllerTest {

    @Mock
    private PasswordService passwordService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Principal principal;

    @InjectMocks
    private PasswordApiController controller;

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
    void requestPasswordReset_shouldReturnOkAndDelegate() {
        PasswordResetRequest request = new PasswordResetRequest("who@test.com");

        ResponseEntity<Void> response = controller.requestPasswordReset(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(passwordService).requestReset("who@test.com");
    }

    @Test
    void resetPassword_shouldReturnOkAndDelegate() {
        PasswordResetConfirmRequest request = new PasswordResetConfirmRequest("tok", "newpass123");

        ResponseEntity<Void> response = controller.resetPassword(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(passwordService).resetPassword("tok", "newpass123");
    }

    @Test
    void changePassword_shouldReturnOkAndDelegate_whenResolved() {
        PasswordChangeRequest request = new PasswordChangeRequest("oldpass123", "newpass123");

        ResponseEntity<Void> response = controller.changePassword(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(passwordService).changePassword(user.getId(), "oldpass123", "newpass123");
    }

    @Test
    void changePassword_shouldThrowNotFound_whenPrincipalHasNoUser() {
        PasswordChangeRequest request = new PasswordChangeRequest("oldpass123", "newpass123");
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.changePassword(request, principal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(user.getEmail());

        verify(passwordService, never()).changePassword(user.getId(), "oldpass123", "newpass123");
    }
}
