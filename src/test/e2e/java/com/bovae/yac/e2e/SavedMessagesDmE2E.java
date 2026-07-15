package com.bovae.yac.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

/** Saved Messages self-DM and friendly DM naming (guards R2-06). */
class SavedMessagesDmE2E extends E2ETestBase {

    @Test
    void savedMessages_opensSelfDm_withFriendlyTitle() {
        BrowserContext ctx = newContext();
        try {
            Page alice = loginDevUser(ctx, ALICE_EMAIL);
            alice.locator("#saved-messages-btn").click();
            alice.waitForURL("**/chat/rooms/**");
            // Never the raw dm-/saved-messages- room name.
            assertThat(alice.locator(".chat-header h6")).hasText("Saved Messages");
        } finally {
            ctx.close();
        }
    }

    @Test
    void directMessage_showsFriendlyLabel_notRawRoomName() {
        BrowserContext ctx = newContext();
        try {
            // Alice and Bob are seeded friends, so Bob shows in the contacts list.
            Page alice = loginDevUser(ctx, ALICE_EMAIL);
            alice.locator("#contact-list").getByText("bob").first().click();
            alice.waitForURL("**/chat/rooms/**");
            assertThat(alice.locator(".chat-header h6"))
                    .containsText("Chat with", new LocatorAssertions.ContainsTextOptions().setTimeout(15000));
        } finally {
            ctx.close();
        }
    }
}
