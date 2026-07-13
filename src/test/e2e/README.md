# End-to-End Tests

## Automated Playwright-for-Java suite (R3-01)

The `*E2E` classes under `src/test/e2e/java` drive a real browser (Chromium) against a running app.
They live behind the opt-in Maven `e2e` profile, so the default `./mvnw verify` never compiles them,
downloads the Playwright driver, or launches a browser.

**Run it:**

```bash
# 1. Start the app (dev profile seeds users alice/bob/carol @dev.local, password devpass123)
docker compose up --build -d

# 2. Run the E2E suite against it (downloads browser binaries on first run)
./mvnw verify -Pe2e

# Point at a different host/port if needed:
./mvnw verify -Pe2e -De2e.baseUrl=http://localhost:8080
# Watch it run in a visible browser:
./mvnw verify -Pe2e -De2e.headed=true
```

The suite reruns against a persistent database; some social scenarios mutate state. For a clean
slate: `docker compose down -v && docker compose up --build -d`.

Coverage: register→login→session, two-context live messaging (send/edit/delete + image),
presence ONLINE-on-load and idle→AFK, live friend request + accept, Saved Messages + DM naming,
attachment ACL for non-members, and admin ban/unban via the Manage Room modal.

---

# Playwright MCP End-to-End Test Workflows (manual)

Step-by-step Playwright MCP tool call workflows for testing the YAC application through a real browser.
Each workflow documents the exact MCP tool calls to execute, the expected page state after each step,
and how to verify success.

**Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8**

---

## Prerequisites

### Starting the Application

The YAC application must be running before executing any workflow. Start it with Docker Compose:

```bash
docker compose up --build -d
```

This starts three services:

| Service    | Port | Purpose                  |
|------------|------|--------------------------|
| `app`      | 8080 | YAC Spring Boot app      |
| `postgres` | 5432 | PostgreSQL 17 database   |
| `redis`    | 6379 | Redis 7 (sessions/cache) |

Wait for all services to be healthy before proceeding:

```bash
docker compose ps
```

Verify the app is reachable:

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/health
# Expected: 200
```

### Stopping the Application

```bash
docker compose down
```

To also remove persisted data (database, file storage):

```bash
docker compose down -v
```

### Test Data Conventions

All workflows use the following test credentials:

| Field    | Value                  |
|----------|------------------------|
| Email    | `testuser@example.com` |
| Username | `testuser`             |
| Password | `TestPass123!`         |

For multi-user workflows, use `testuser2@example.com` / `testuser2` / `TestPass123!`.

---

## Workflow 1: Registration Flow

**Validates: Requirement 11.2** — Navigate to `/register`, fill form, submit, verify redirect to login.

### Steps

**1. Navigate to the registration page**

```
Tool: mcp_playwright_browser_navigate
  url: "http://localhost:8080/register"
```

**2. Take a snapshot to identify form elements**

```
Tool: mcp_playwright_browser_snapshot
```

Expected elements:
- Heading: "Register"
- Text fields: Email (`#email`), Username (`#username`), Password (`#password`), Confirm password (`#confirmPassword`)
- Button: "Create account"

**3. Fill in the registration form**

```
Tool: mcp_playwright_browser_fill_form
  fields:
    - name: "Email"
      type: "textbox"
      ref: <ref for #email input>
      value: "testuser@example.com"
    - name: "Username"
      type: "textbox"
      ref: <ref for #username input>
      value: "testuser"
    - name: "Password"
      type: "textbox"
      ref: <ref for #password input>
      value: "TestPass123!"
    - name: "Confirm password"
      type: "textbox"
      ref: <ref for #confirmPassword input>
      value: "TestPass123!"
```

**4. Submit the form**

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Create account" button>
  element: "Create account button"
```

**5. Verify redirect to login page**

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- URL contains `/login`
- Page heading: "Sign In"
- A success alert is visible (e.g., "Registration successful")

### Success Criteria

- The registration form submits without errors
- The browser redirects to `/login` after successful registration
- A success message is displayed on the login page

---

## Workflow 2: Login Flow

**Validates: Requirement 11.3** — Navigate to `/login`, enter credentials, submit, verify redirect to `/chat`.

### Prerequisites

Complete Workflow 1 (Registration) first so the user account exists.

### Steps

**1. Navigate to the login page**

```
Tool: mcp_playwright_browser_navigate
  url: "http://localhost:8080/login"
```

**2. Take a snapshot to identify form elements**

```
Tool: mcp_playwright_browser_snapshot
```

Expected elements:
- Heading: "Sign In"
- Text fields: Email (`#email`), Password (`#password`)
- Checkbox: "Keep me signed in" (`#remember-me`)
- Button: "Sign in"

**3. Fill in login credentials**

```
Tool: mcp_playwright_browser_fill_form
  fields:
    - name: "Email"
      type: "textbox"
      ref: <ref for #email input>
      value: "testuser@example.com"
    - name: "Password"
      type: "textbox"
      ref: <ref for #password input>
      value: "TestPass123!"
```

**4. Submit the login form**

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Sign in" button>
  element: "Sign in button"
```

**5. Verify redirect to chat page**

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- URL contains `/chat`
- Page title: "Chat - YAC"
- Navbar is visible with links: "Public Rooms", "Private Rooms", "Contacts", "Sessions", "Profile"
- Sidebar is visible with "Create room" and "Browse" buttons
- "Sign out" button is visible in the navbar

### Success Criteria

- Login form submits without errors
- Browser redirects to `/chat`
- The authenticated chat UI is displayed with navbar and sidebar

---

## Workflow 3: Room Creation Flow

**Validates: Requirement 11.4** — From chat page, create a public room, verify room appears in sidebar.

### Prerequisites

Complete Workflow 2 (Login) so the user is authenticated and on the `/chat` page.

### Steps

**1. Click "Create room" in the sidebar**

```
Tool: mcp_playwright_browser_snapshot
```

Locate the "Create room" link in the sidebar.

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Create room" link>
  element: "Create room link in sidebar"
```

**2. Verify the room creation page loads**

```
Tool: mcp_playwright_browser_snapshot
```

Expected elements:
- Heading: "Create a Room"
- Text field: Room Name (`#name`, required, maxlength 100)
- Textarea: Description (`#description`)
- Select: Visibility (`#visibility`) with options "Public" and "Private"
- Button: "Create Room"
- Link: "Cancel" (goes to `/rooms/catalog`)

**3. Fill in room details**

```
Tool: mcp_playwright_browser_fill_form
  fields:
    - name: "Room Name"
      type: "textbox"
      ref: <ref for #name input>
      value: "General Chat"
    - name: "Description"
      type: "textbox"
      ref: <ref for #description textarea>
      value: "A public room for general discussion"
```

Ensure visibility is set to "Public" (it is the default):

```
Tool: mcp_playwright_browser_select_option
  ref: <ref for #visibility select>
  element: "Visibility dropdown"
  values: ["PUBLIC"]
```

**4. Submit the room creation form**

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Create Room" button>
  element: "Create Room submit button"
```

**5. Verify redirect to the new room's chat view**

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- URL contains `/chat/rooms/` followed by a UUID
- The room name "General Chat" is visible in the chat header or page
- The sidebar shows the room under "Public Rooms"
- The message input area is visible with a textarea and "Send" button

**6. Navigate back to `/chat` and verify room in sidebar**

```
Tool: mcp_playwright_browser_navigate
  url: "http://localhost:8080/chat"
```

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- "General Chat" appears in the "Public Rooms" section of the sidebar

### Success Criteria

- Room creation form submits without a 403 error (CSRF fix is in place)
- Browser redirects to the new room's chat view
- The room appears in the sidebar under "Public Rooms"

---

## Workflow 4: Messaging Flow

**Validates: Requirement 11.5** — Enter a room, send a message, verify message appears.

### Prerequisites

Complete Workflow 3 (Room Creation) so a room exists and the user is a member.

### Steps

**1. Navigate to the room (if not already there)**

If you completed Workflow 3, navigate to the room:

```
Tool: mcp_playwright_browser_snapshot
```

Click on "General Chat" in the sidebar to enter the room.

```
Tool: mcp_playwright_browser_click
  ref: <ref for "General Chat" room link in sidebar>
  element: "General Chat room link"
```

**2. Verify the room chat view is loaded**

```
Tool: mcp_playwright_browser_snapshot
```

Expected elements:
- Message input textarea (`#message-textarea`) with placeholder "Type a message..."
- "Send" button (`#send-btn`)
- Message list area (`#message-list`)

**3. Type a message**

```
Tool: mcp_playwright_browser_click
  ref: <ref for #message-textarea>
  element: "Message input textarea"
```

```
Tool: mcp_playwright_browser_type
  ref: <ref for #message-textarea>
  text: "Hello, this is a test message!"
```

**4. Send the message**

```
Tool: mcp_playwright_browser_click
  ref: <ref for #send-btn>
  element: "Send button"
```

Alternatively, press Enter to send:

```
Tool: mcp_playwright_browser_press_key
  key: "Enter"
```

**5. Verify the message appears in the message list**

Wait briefly for the WebSocket round-trip:

```
Tool: mcp_playwright_browser_wait_for
  text: "Hello, this is a test message!"
```

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- The message "Hello, this is a test message!" appears in the `#message-list` area
- The message shows the sender username "testuser"
- A timestamp is displayed next to the username
- The message input textarea is cleared

### Success Criteria

- Message is sent via WebSocket (STOMP) without errors
- The message appears in the message list with correct sender and content
- The input field is cleared after sending

---

## Workflow 5: Catalog Search Flow

**Validates: Requirement 11.6** — Navigate to catalog, search by name, verify results.

### Prerequisites

Complete Workflow 3 (Room Creation) so at least one public room ("General Chat") exists.

### Steps

**1. Navigate to the room catalog**

From the chat page, click "Browse" in the sidebar:

```
Tool: mcp_playwright_browser_snapshot
```

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Browse" link in sidebar>
  element: "Browse catalog link"
```

Or navigate directly:

```
Tool: mcp_playwright_browser_navigate
  url: "http://localhost:8080/rooms/catalog"
```

**2. Verify the catalog page loads**

```
Tool: mcp_playwright_browser_snapshot
```

Expected elements:
- Heading: "Public Room Catalog"
- Search input with placeholder "Search rooms..."
- "Search" button
- "Create Room" link
- Room list showing "General Chat" with description and member count

**3. Search for the room by name**

```
Tool: mcp_playwright_browser_fill_form
  fields:
    - name: "Search"
      type: "textbox"
      ref: <ref for search input>
      value: "General"
```

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Search" button>
  element: "Search button"
```

**4. Verify search results**

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- URL contains `/rooms/catalog?search=General`
- "General Chat" appears in the results list
- The room entry shows the name, description ("A public room for general discussion"), and member count
- A "Join" link is displayed for the room

**5. Search for a non-existent room**

```
Tool: mcp_playwright_browser_fill_form
  fields:
    - name: "Search"
      type: "textbox"
      ref: <ref for search input>
      value: "NonExistentRoom12345"
```

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Search" button>
  element: "Search button"
```

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- "No public rooms found." message is displayed
- A "Create the first room" link is shown

### Success Criteria

- Catalog page displays public rooms
- Search filters rooms by name correctly
- Matching rooms show name, description, and member count
- Empty search results display an appropriate message

---

## Workflow 6: Logout Flow

**Validates: Requirement 11.7** — Click logout, verify redirect to login.

### Prerequisites

The user must be logged in (complete Workflow 2).

### Steps

**1. Take a snapshot to locate the Sign out button**

```
Tool: mcp_playwright_browser_snapshot
```

The "Sign out" button is in the navbar (a `<form>` with POST to `/logout`).

**2. Click the Sign out button**

```
Tool: mcp_playwright_browser_click
  ref: <ref for "Sign out" button>
  element: "Sign out button in navbar"
```

**3. Verify redirect to login page**

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- URL contains `/login`
- Page heading: "Sign In"
- A logout success alert is visible: "You have been signed out."
- The navbar shows "Sign in" and "Register" links (unauthenticated state)

**4. Verify authenticated pages are no longer accessible**

```
Tool: mcp_playwright_browser_navigate
  url: "http://localhost:8080/chat"
```

```
Tool: mcp_playwright_browser_snapshot
```

Expected state:
- URL redirects to `/login` (unauthenticated users cannot access `/chat`)

### Success Criteria

- Clicking "Sign out" submits the logout form
- Browser redirects to `/login` with a success message
- Subsequent access to `/chat` redirects back to `/login`

---

## Full End-to-End Sequence

Run all workflows in order for a complete E2E test:

1. **Registration** — Create a new account
2. **Login** — Sign in with the new account
3. **Room Creation** — Create a public room called "General Chat"
4. **Messaging** — Send a message in the room
5. **Catalog Search** — Search for the room in the catalog
6. **Logout** — Sign out and verify session is terminated

### Cleanup

After running all workflows, stop the application and remove test data:

```bash
docker compose down -v
```

---

## Troubleshooting

| Issue | Cause | Fix |
|---|---|---|
| Cannot connect to `localhost:8080` | App not running or still starting | Run `docker compose up -d` and wait for healthchecks |
| 403 on room creation | Missing CSRF meta tags | Verify `rooms/create.html` has `_csrf` and `_csrf_header` meta tags |
| Login redirects back to `/login` | Invalid credentials or user not registered | Run the Registration workflow first |
| Messages not appearing | WebSocket connection failed | Check browser console for STOMP connection errors |
| Catalog shows no rooms | No public rooms created yet | Run the Room Creation workflow first |
