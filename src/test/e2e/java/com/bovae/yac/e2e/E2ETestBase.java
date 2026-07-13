package com.bovae.yac.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Shared harness for the Playwright browser E2E suite (R3-01). Drives a running app at
 * {@code e2e.baseUrl} (default {@code http://localhost:8080}) started via {@code docker compose up}.
 *
 * <p>One Chromium instance is shared across a test class; each test opens its own
 * {@link BrowserContext} so two-user scenarios stay isolated. All waiting relies on Playwright
 * auto-waiting assertions/locators — never {@code sleep} (R1-53).
 *
 * <p>System properties: {@code -De2e.baseUrl=...}, {@code -De2e.headed=true} for a visible browser.
 */
public abstract class E2ETestBase {

    protected static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8080");

    /** Documented dev password for every seeded user (migration 005-dev-seed-data). */
    protected static final String DEV_PASSWORD = "devpass123";

    protected static final String ALICE_EMAIL = "alice@dev.local";
    protected static final String BOB_EMAIL = "bob@dev.local";
    protected static final String CAROL_EMAIL = "carol@dev.local";

    /** Seeded public room "General" (migration 005). */
    protected static final String GENERAL_ROOM_ID = "33333333-3333-3333-3333-333333333333";
    protected static final String ALICE_ID = "11111111-1111-1111-1111-111111111111";
    protected static final String BOB_ID = "22222222-2222-2222-2222-222222222222";
    protected static final String CAROL_ID = "44444444-4444-4444-4444-444444444444";

    private static Playwright playwright;
    protected static Browser browser;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        boolean headed = Boolean.parseBoolean(System.getProperty("e2e.headed", "false"));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(!headed));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    protected BrowserContext newContext() {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions().setBaseURL(BASE_URL));
        context.setDefaultTimeout(15000);
        return context;
    }

    /** Log a user in and land on the chat page. */
    protected Page login(BrowserContext context, String email, String password) {
        Page page = context.newPage();
        page.navigate("/login");
        page.locator("#email").fill(email);
        page.locator("#password").fill(password);
        page.locator("button[type=submit]").click();
        page.waitForURL("**/chat");
        return page;
    }

    /** Log a seeded dev user in using the shared dev password. */
    protected Page loginDevUser(BrowserContext context, String email) {
        return login(context, email, DEV_PASSWORD);
    }

    /** Register a brand-new account; leaves the page on /login. */
    protected void register(Page page, String email, String username, String password) {
        page.navigate("/register");
        page.locator("#email").fill(email);
        page.locator("#username").fill(username);
        page.locator("#password").fill(password);
        page.locator("#confirmPassword").fill(password);
        page.locator("button[type=submit]").click();
        page.waitForURL("**/login**");
    }

    protected void openRoom(Page page, String roomId) {
        page.navigate("/chat/rooms/" + roomId);
        page.waitForURL("**/chat/rooms/" + roomId);
    }
}
