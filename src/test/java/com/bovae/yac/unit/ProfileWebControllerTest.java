package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.web.ProfileWebController;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.AuthService;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

/**
 * Unit tests for {@link ProfileWebController}.
 */
@ExtendWith(MockitoExtension.class)
class ProfileWebControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthService authService;

    @Mock
    private Principal principal;

    @InjectMocks
    private ProfileWebController controller;

    @Test
    void profile_shouldAddUserToModelAndReturnView() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("alice@test.com")
                .username("alice")
                .passwordHash("hash")
                .build();
        when(principal.getName()).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        Model model = new ExtendedModelMap();

        String view = controller.profile(model, principal);

        assertThat(view).isEqualTo("profile/index");
        assertThat(model.getAttribute("user")).isEqualTo(user);
    }

    @Test
    void sessions_shouldAddSessionsToModelAndReturnView() {
        List<AuthService.SessionInfo> sessions = List.of();
        when(principal.getName()).thenReturn("alice@test.com");
        when(authService.listSessions("alice@test.com")).thenReturn(sessions);
        Model model = new ExtendedModelMap();

        String view = controller.sessions(model, principal);

        assertThat(view).isEqualTo("profile/sessions");
        assertThat(model.getAttribute("sessions")).isEqualTo(sessions);
    }
}
