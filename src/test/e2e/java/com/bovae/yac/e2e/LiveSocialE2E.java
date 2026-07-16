package com.bovae.yac.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

/**
 * Live (no-reload) propagation of social and membership events over WebSocket, guarding the
 * round-R5 fixes: reply-preview refresh on edit (R5-01), member-list live join (R5-03), contact
 * removal fan-out (R5-04) and room-invitation live arrival (R5-06). Plus the sidebar search
 * filter regression (R5-05), which is client-only but was a reported break.
 */
class LiveSocialE2E extends E2ETestBase {

    private static final LocatorAssertions.IsVisibleOptions VISIBLE_SLOW =
            new LocatorAssertions.IsVisibleOptions().setTimeout(20000);
    private static final LocatorAssertions.ContainsTextOptions CONTAINS_SLOW =
            new LocatorAssertions.ContainsTextOptions().setTimeout(20000);

    private Page registerAndLogin(BrowserContext ctx, String user) {
        Page page = ctx.newPage();
        register(page, user + "@test.local", user, "TestPass123!");
        page.locator("#email").fill(user + "@test.local");
        page.locator("#password").fill("TestPass123!");
        page.locator("button[type=submit]").click();
        page.waitForURL("**/chat");
        return page;
    }

    private String createRoom(Page page, String name, String visibility) {
        page.navigate("/rooms/create");
        page.locator("#name").fill(name);
        page.locator("#visibility").selectOption(visibility);
        page.locator("#create-room-form button[type=submit]").click();
        page.waitForURL("**/chat/rooms/**");
        String url = page.url();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    void replyPreview_updatesLive_whenReferencedMessageIsEdited() {
        BrowserContext aliceCtx = newContext();
        BrowserContext bobCtx = newContext();
        try {
            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            Page bob = loginDevUser(bobCtx, BOB_EMAIL);
            openRoom(alice, GENERAL_ROOM_ID);
            openRoom(bob, GENERAL_ROOM_ID);

            String original = "r5orig-" + System.currentTimeMillis();
            alice.locator("#message-textarea").fill(original);
            alice.locator("#send-btn").click();

            // Bob replies to Alice's message; the reply-quote snapshots the original text.
            Locator bobOriginal = bob.locator(".message-item").filter(new Locator.FilterOptions().setHasText(original));
            assertThat(bobOriginal).isVisible(VISIBLE_SLOW);
            bobOriginal.locator(".reply-btn").click();
            bob.locator("#message-textarea").fill("r5reply");
            bob.locator("#send-btn").click();
            assertThat(bob.locator(".reply-quote").filter(new Locator.FilterOptions().setHasText(original)))
                    .isVisible(VISIBLE_SLOW);

            // Alice edits the original; Bob's reply-preview must follow live (R5-01).
            String edited = original + "-edited";
            Locator aliceOriginal =
                    alice.locator(".message-item").filter(new Locator.FilterOptions().setHasText(original));
            aliceOriginal.locator(".edit-btn").click();
            aliceOriginal.locator(".edit-textarea").fill(edited);
            aliceOriginal.getByText("Save").click();

            assertThat(bob.locator(".reply-quote").filter(new Locator.FilterOptions().setHasText(edited)))
                    .isVisible(VISIBLE_SLOW);
        } finally {
            aliceCtx.close();
            bobCtx.close();
        }
    }

    @Test
    void memberList_addsJoiner_liveWithoutReload() {
        BrowserContext aliceCtx = newContext();
        BrowserContext joinerCtx = newContext();
        try {
            String ts = Long.toString(System.currentTimeMillis());
            String joiner = "r5join" + ts;

            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            String roomId = createRoom(alice, "r5-join-" + ts, "PUBLIC");
            openRoom(alice, roomId);

            // A newcomer joins the public room; Alice's member list grows live (R5-03).
            Page joinerPage = registerAndLogin(joinerCtx, joiner);
            joinerPage.navigate("/chat/rooms/" + roomId);
            joinerPage.locator("button:has-text('Join Room')").click();
            joinerPage.waitForURL("**/chat/rooms/" + roomId);

            assertThat(alice.locator("#member-list-items")).containsText(joiner, CONTAINS_SLOW);
        } finally {
            aliceCtx.close();
            joinerCtx.close();
        }
    }

    @Test
    void contactRemoval_propagatesLive_toTheOtherUser() {
        BrowserContext xCtx = newContext();
        BrowserContext yCtx = newContext();
        try {
            String ts = Long.toString(System.currentTimeMillis());
            String xUser = "r5x" + ts;
            String yUser = "r5y" + ts;

            Page x = registerAndLogin(xCtx, xUser);
            Page y = registerAndLogin(yCtx, yUser);

            // X befriends Y (add-contact is collapsed behind the People "＋" after R5-18).
            x.locator("[data-bs-target='#addContactPanel']").click();
            x.locator("#add-friend-username").fill(yUser);
            x.locator("form:has(#add-friend-username) button[type=submit]").click();
            assertThat(y.locator("#friend-requests-badge")).isVisible(VISIBLE_SLOW);
            y.locator("#friendRequestsCollapse").getByText("Accept").click();
            assertThat(x.locator("#contact-list")).containsText(yUser, CONTAINS_SLOW);
            assertThat(y.locator("#contact-list")).containsText(xUser, CONTAINS_SLOW);

            // X removes the contact (× opens the in-app confirm modal, R5-09) and confirms.
            x.locator("#contact-list li")
                    .filter(new Locator.FilterOptions().setHasText(yUser))
                    .locator("button")
                    .click();
            x.locator("#confirmModalConfirmBtn").click();

            // Y's list must clear live, not on reload (R5-04).
            assertThat(y.locator("#contact-list")).not().containsText(xUser, CONTAINS_SLOW);
        } finally {
            xCtx.close();
            yCtx.close();
        }
    }

    @Test
    void roomInvitation_arrivesLive_inTheInviteesPanel() {
        BrowserContext aliceCtx = newContext();
        BrowserContext inviteeCtx = newContext();
        try {
            String ts = Long.toString(System.currentTimeMillis());
            String roomName = "r5-invite-" + ts;
            String invitee = "r5inv" + ts;

            // Invitee is registered first so Alice can invite them by username.
            Page inviteePage = registerAndLogin(inviteeCtx, invitee);

            Page alice = loginDevUser(aliceCtx, ALICE_EMAIL);
            String roomId = createRoom(alice, roomName, "PRIVATE");
            openRoom(alice, roomId);

            // Alice invites via the single Manage Room modal's Invitations tab (R2-08).
            alice.locator("[data-bs-target='#manageRoomModal']").click();
            alice.locator("#invitations-tab").click();
            alice.locator("#invite-username").fill(invitee);
            alice.getByText("Send Invitation").click();

            // Invitee's Room Invitations panel appears live without a reload (R5-06).
            assertThat(inviteePage.locator("#room-invitations-section")).containsText(roomName, CONTAINS_SLOW);
        } finally {
            aliceCtx.close();
            inviteeCtx.close();
        }
    }

    @Test
    void sidebarSearch_hidesNonMatchingRooms() {
        BrowserContext ctx = newContext();
        try {
            Page page = loginDevUser(ctx, ALICE_EMAIL);
            page.navigate("/chat");
            // "General" is a seeded public room; a non-matching term must actually hide it, not
            // merely set an inline display that Bootstrap's d-flex !important overrides (R5-05).
            Locator general =
                    page.locator("#public-room-list li").filter(new Locator.FilterOptions().setHasText("General"));
            assertThat(general).isVisible(VISIBLE_SLOW);
            page.locator("input[placeholder*='Search']").fill("zzz-no-such-room");
            assertThat(general).isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(10000));
        } finally {
            ctx.close();
        }
    }
}
