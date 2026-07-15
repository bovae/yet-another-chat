package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bovae.yac.controller.web.AuthWebController;
import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.service.PasswordService;
import com.bovae.yac.service.UserService;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Unit tests for {@link AuthWebController}.
 */
@ExtendWith(MockitoExtension.class)
class AuthWebControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private PasswordService passwordService;

    @Mock
    private RedirectAttributes redirectAttributes;

    @InjectMocks
    private AuthWebController controller;

    // --- GET pages ---

    @Test
    void forgotPassword_shouldReturnForgotPasswordView() {
        assertThat(controller.forgotPassword()).isEqualTo("auth/forgot-password");
    }

    @Test
    void resetPassword_shouldAddTokenToModelAndReturnView() {
        Model model = new ExtendedModelMap();

        String view = controller.resetPassword("reset-token", model);

        assertThat(view).isEqualTo("auth/reset-password");
        assertThat(model.getAttribute("token")).isEqualTo("reset-token");
    }

    // --- registerPost ---

    @ParameterizedTest(name = "password={0}, confirm={1} -> error \"{2}\"")
    @MethodSource("invalidRegistrations")
    void registerPost_shouldReturnRegisterViewWithError_whenValidationFails(
            String password, String confirmPassword, String expectedError) {
        Model model = new ExtendedModelMap();

        String view =
                controller.registerPost("e@test.com", "user", password, confirmPassword, redirectAttributes, model);

        assertThat(view).isEqualTo("auth/register");
        assertThat(model.getAttribute("error")).isEqualTo(expectedError);
        assertThat(model.getAttribute("email")).isEqualTo("e@test.com");
        assertThat(model.getAttribute("username")).isEqualTo("user");
        verifyNoInteractions(userService, redirectAttributes);
    }

    private static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of("short", "short", "Password must be at least 8 characters"),
                Arguments.of("password123", "different99", "Passwords do not match"));
    }

    @Test
    void registerPost_shouldRegisterAndRedirectToLogin_whenPasswordExactlyMinLength() {
        // Password is exactly 8 chars: the minimum-length guard rejects only shorter passwords.
        Model model = new ExtendedModelMap();

        String view =
                controller.registerPost("new@test.com", "newuser", "pass1234", "pass1234", redirectAttributes, model);

        assertThat(view).isEqualTo("redirect:/login");
        verify(userService).register("new@test.com", "newuser", "pass1234");
        verify(redirectAttributes).addFlashAttribute("success", "Account created. Please sign in.");
    }

    @Test
    void registerPost_shouldReturnRegisterViewWithError_whenEmailAlreadyRegistered() {
        when(userService.register("dup@test.com", "dupuser", "password123"))
                .thenThrow(new ConflictException("Email already registered"));
        Model model = new ExtendedModelMap();

        String view = controller.registerPost(
                "dup@test.com", "dupuser", "password123", "password123", redirectAttributes, model);

        assertThat(view).isEqualTo("auth/register");
        assertThat(model.getAttribute("error")).isEqualTo("Email already registered");
        assertThat(model.getAttribute("email")).isEqualTo("dup@test.com");
        assertThat(model.getAttribute("username")).isEqualTo("dupuser");
        verifyNoInteractions(redirectAttributes);
    }

    // --- forgotPasswordPost ---

    @Test
    void forgotPasswordPost_shouldRequestResetAndRedirect() {
        String view = controller.forgotPasswordPost("someone@test.com", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/forgot-password");
        verify(passwordService).requestReset("someone@test.com");
        verify(redirectAttributes)
                .addFlashAttribute(
                        "success", "If an account with that email exists, a password reset link has been sent.");
    }
}
