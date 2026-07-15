package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovae.yac.controller.web.HomeWebController;
import java.security.Principal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HomeWebController}.
 */
class HomeWebControllerTest {

    private final HomeWebController controller = new HomeWebController();

    @Test
    void home_shouldRedirectToChat_whenPrincipalPresent() {
        Principal principal = () -> "alice@test.com";

        assertThat(controller.home(principal)).isEqualTo("redirect:/chat");
    }

    @Test
    void home_shouldRedirectToLogin_whenPrincipalNull() {
        assertThat(controller.home(null)).isEqualTo("redirect:/login");
    }
}
