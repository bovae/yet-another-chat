package com.bovae.yac.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Register → login → persistent session (guards the auth flow, R3-01). */
class AuthSessionE2E extends E2ETestBase {

    @Test
    void register_login_and_session_persists() {
        BrowserContext context = newContext();
        try {
            Page page = context.newPage();
            String suffix = Long.toString(System.currentTimeMillis());
            String email = "e2e-" + suffix + "@test.local";
            String username = "e2e" + suffix;

            register(page, email, username, "TestPass123!");

            page.locator("#email").fill(email);
            page.locator("#password").fill("TestPass123!");
            page.locator("button[type=submit]").click();
            page.waitForURL("**/chat");

            // The session cookie carries across a fresh navigation — no bounce back to /login.
            page.navigate("/rooms/catalog");
            page.waitForURL("**/rooms/catalog");
            // The notification bell renders only for an authenticated user.
            assertThat(page.locator("#navbar-notif")).isVisible();
        } finally {
            context.close();
        }
    }
}
