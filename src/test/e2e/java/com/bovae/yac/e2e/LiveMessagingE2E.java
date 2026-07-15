package com.bovae.yac.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Two-browser live messaging: send, edit, delete propagation and live image rendering
 * (guards R1-01/03/04/05, R3-01).
 */
class LiveMessagingE2E extends E2ETestBase {

    // 1x1 transparent PNG.
    private static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @Test
    void messages_edits_deletes_and_images_propagate_live() {
        BrowserContext aliceCtx = newContext();
        BrowserContext bobCtx = newContext();
        try {
            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            Page bob = loginDevUser(bobCtx, BOB_EMAIL);
            openRoom(alice, GENERAL_ROOM_ID);
            openRoom(bob, GENERAL_ROOM_ID);

            // --- send ---
            String text = "hello-" + System.currentTimeMillis();
            alice.locator("#message-textarea").fill(text);
            alice.locator("#send-btn").click();
            assertThat(bob.locator("#message-list").getByText(text)).isVisible();

            // --- edit (propagates in place, not as a new bubble) ---
            String edited = text + "-edited";
            Locator aliceMsg = alice.locator(".message-item").filter(new Locator.FilterOptions().setHasText(text));
            aliceMsg.locator(".edit-btn").click();
            Locator editArea = aliceMsg.locator(".edit-textarea");
            editArea.fill(edited);
            aliceMsg.getByText("Save").click();
            assertThat(bob.locator("#message-list").getByText(edited)).isVisible();

            // --- delete (propagates as removal) ---
            Locator bobEdited = bob.locator("#message-list").getByText(edited);
            alice.locator(".message-item")
                    .filter(new Locator.FilterOptions().setHasText(edited))
                    .locator(".delete-btn")
                    .click();
            alice.locator("#confirmModalConfirmBtn").click();
            assertThat(bobEdited).hasCount(0);

            // --- image upload renders live for the other user ---
            alice.locator("#image-input").setInputFiles(new FilePayload("pic.png", "image/png", PNG));
            assertThat(bob.locator("#message-list img").first()).isVisible();
        } finally {
            aliceCtx.close();
            bobCtx.close();
        }
    }
}
