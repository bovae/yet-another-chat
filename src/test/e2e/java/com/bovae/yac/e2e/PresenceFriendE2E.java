package com.bovae.yac.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

/**
 * Presence-on-load / idle→AFK (guards R2-01) and live friend-request delivery + accept
 * (guards R2-03).
 */
class PresenceFriendE2E extends E2ETestBase {

    private String aliceDot() {
        return ".presence-dot[data-user-id='" + ALICE_ID + "']";
    }

    @Test
    void freshlyLoadedTab_readsOnline_withoutInput() {
        BrowserContext bobCtx = newContext();
        BrowserContext aliceCtx = newContext();
        try {
            // Bob watches the General member list.
            Page bob = loginDevUser(bobCtx, BOB_EMAIL);
            openRoom(bob, GENERAL_ROOM_ID);

            // Alice loads the room and touches nothing.
            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            openRoom(alice, GENERAL_ROOM_ID);

            // Bob sees Alice ONLINE (green dot) purely from page load (R2-01).
            assertThat(bob.locator(aliceDot() + ".bg-success").first())
                    .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        } finally {
            aliceCtx.close();
            bobCtx.close();
        }
    }

    @Test
    void idleTab_flipsToAfk_after60s() {
        BrowserContext bobCtx = newContext();
        BrowserContext aliceCtx = newContext();
        try {
            Page bob = loginDevUser(bobCtx, BOB_EMAIL);
            openRoom(bob, GENERAL_ROOM_ID);
            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            openRoom(alice, GENERAL_ROOM_ID);

            assertThat(bob.locator(aliceDot() + ".bg-success").first())
                    .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

            // No interaction in Alice's tab → AFK (amber dot). Server freezes lastActive at her
            // last active heartbeat, so the transition lands ~120s later (60s idle threshold
            // measured from that heartbeat) — allow generous margin.
            assertThat(bob.locator(aliceDot() + ".bg-warning").first())
                    .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(150000));
        } finally {
            aliceCtx.close();
            bobCtx.close();
        }
    }

    @Test
    void friendRequest_arrivesLive_andAcceptPersists() {
        BrowserContext xCtx = newContext();
        BrowserContext yCtx = newContext();
        try {
            String suffix = Long.toString(System.currentTimeMillis());
            String xUser = "efx" + suffix;
            String yUser = "efy" + suffix;

            Page x = xCtx.newPage();
            register(x, xUser + "@test.local", xUser, "TestPass123!");
            x.locator("#email").fill(xUser + "@test.local");
            x.locator("#password").fill("TestPass123!");
            x.locator("button[type=submit]").click();
            x.waitForURL("**/chat");

            Page y = yCtx.newPage();
            register(y, yUser + "@test.local", yUser, "TestPass123!");
            y.locator("#email").fill(yUser + "@test.local");
            y.locator("#password").fill("TestPass123!");
            y.locator("button[type=submit]").click();
            y.waitForURL("**/chat");

            // X sends Y a friend request from the sidebar (add-contact is collapsed behind the
            // People "＋" after the R5-18 sidebar reorg).
            x.locator("[data-bs-target='#addContactPanel']").click();
            x.locator("#add-friend-username").fill(yUser);
            x.locator("form:has(#add-friend-username) button[type=submit]").click();

            // Y's Friend Requests badge appears live, without a reload (R2-03).
            assertThat(y.locator("#friend-requests-badge"))
                    .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

            // Y accepts; X's contacts refresh to include Y.
            y.locator("#friendRequestsCollapse").getByText("Accept").click();
            assertThat(x.locator("#contact-list"))
                    .containsText(yUser, new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        } finally {
            xCtx.close();
            yCtx.close();
        }
    }
}
