package com.bovae.yac.e2e;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Admin ban/unban through the consolidated Manage Room modal (guards R2-08) and attachment
 * ACL enforcement for non-members (guards R1-56 / attachment access control).
 */
class RoomAdminAclE2E extends E2ETestBase {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private String createRoom(Page page, String name, String visibility) {
        page.navigate("/rooms/create");
        page.locator("#name").fill(name);
        page.locator("#visibility").selectOption(visibility);
        // Scope to the form — the authenticated navbar's "Sign out" is also a submit button.
        page.locator("#create-room-form button[type=submit]").click();
        page.waitForURL("**/chat/rooms/**");
        String url = page.url();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private Page registerAndLogin(BrowserContext ctx, String user) {
        Page page = ctx.newPage();
        register(page, user + "@test.local", user, "TestPass123!");
        page.locator("#email").fill(user + "@test.local");
        page.locator("#password").fill("TestPass123!");
        page.locator("button[type=submit]").click();
        page.waitForURL("**/chat");
        return page;
    }

    @Test
    void admin_bansAndUnbansMember_viaManageRoomModal() {
        BrowserContext aliceCtx = newContext();
        BrowserContext zCtx = newContext();
        try {
            String ts = Long.toString(System.currentTimeMillis());
            String zUser = "eba" + ts;

            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            String roomId = createRoom(alice, "e2e-admin-" + ts, "PUBLIC");

            // Z joins the public room.
            Page z = registerAndLogin(zCtx, zUser);
            z.navigate("/chat/rooms/" + roomId);
            z.locator("button:has-text('Join Room')").click();
            z.waitForURL("**/chat/rooms/" + roomId);

            // Alice bans Z from the Members tab of the single Manage Room modal (R2-08).
            openRoom(alice, roomId);
            alice.locator("[data-bs-target='#manageRoomModal']").click();
            alice.locator("#manage-members-list").getByText("Ban").first().click();
            alice.locator("#confirmModalConfirmBtn").click();
            alice.waitForLoadState(LoadState.LOAD);

            // Banned tab lists Z; unban removes them.
            alice.locator("[data-bs-target='#manageRoomModal']").click();
            alice.locator("#banned-tab").click();
            Locator bannedList = alice.locator("#banned-users-list");
            assertThat(bannedList).containsText(zUser,
                    new LocatorAssertions.ContainsTextOptions().setTimeout(15000));

            bannedList.getByText("Unban").first().click();
            assertThat(bannedList).not().containsText(zUser);
        } finally {
            aliceCtx.close();
            zCtx.close();
        }
    }

    @Test
    void attachment_inPrivateRoom_deniedToNonMember() {
        BrowserContext aliceCtx = newContext();
        BrowserContext zCtx = newContext();
        try {
            String ts = Long.toString(System.currentTimeMillis());

            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            createRoom(alice, "e2e-acl-" + ts, "PRIVATE");
            alice.locator("#image-input").setInputFiles(new FilePayload("p.png", "image/png", PNG));
            Locator img = alice.locator("#message-list img").first();
            assertThat(img).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));
            String downloadPath = img.getAttribute("src");

            // A logged-in non-member cannot download the private room's attachment.
            registerAndLogin(zCtx, "eacl" + ts);
            APIResponse response = zCtx.request().get(BASE_URL + downloadPath);
            assertEquals(403, response.status(), "non-member must be denied the attachment");
        } finally {
            aliceCtx.close();
            zCtx.close();
        }
    }
}
