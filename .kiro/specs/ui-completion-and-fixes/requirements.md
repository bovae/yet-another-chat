# Requirements Document

## Introduction

YAC (Yet Another Chat) has a largely complete backend but significant gaps in the frontend/UI layer. This specification covers 18 discrete items: missing pages, unwired UI controls, absent JavaScript logic, a missing API endpoint, a backend bug, and navigation link corrections. All work targets the existing Thymeleaf + Bootstrap 5 + STOMP.js + vanilla JS stack with no new external dependencies.

## Glossary

- **Application**: The YAC Spring Boot web application as a whole
- **Reset_Page**: The Thymeleaf-rendered page at `/reset-password` where a user submits a token and new password
- **Room_Settings_UI**: The modal or tab within the room view that allows editing room name, description, and visibility
- **Room_API**: The REST controller at `/api/rooms` responsible for room CRUD operations
- **Friend_Request_API**: The REST controller at `/api/friends/request` responsible for sending friend requests
- **Sidebar**: The left-hand navigation panel rendered by the `sidebar` Thymeleaf fragment, displaying rooms and contacts
- **Emoji_Picker**: A popup grid of emoji characters triggered by the 😊 button in the message input area
- **Typing_Indicator**: A UI element below the message list that shows which users are currently typing
- **Message_Edit_UI**: The inline editing flow that allows a user to modify the content of their own sent messages
- **Message_Delete_Handler**: The JavaScript click handler wired to the 🗑 button on a user's own messages
- **Banned_Users_Modal**: The Bootstrap modal that lists banned users for a room and provides unban functionality
- **Profile_Forms_JS**: The JavaScript logic that submits the display name, password change, and account deletion forms on the profile page
- **Sidebar_Search**: The filtering logic wired to the search input in the sidebar
- **Friend_Request_Option**: A dropdown menu item in the room member list that sends a friend request to another member
- **Friend_Request_Panel**: A UI section that displays pending incoming and outgoing friend requests with accept/decline actions
- **Contact_DM_Link**: The clickable contact entry in the sidebar that initiates or opens a direct message conversation
- **Attachment_Renderer**: The message rendering logic that displays uploaded images inline and provides download links for other file types
- **Leave_Room_Button**: A UI button in the room member panel that allows the current user to leave the room
- **AFK_Coordinator**: The client-side JavaScript module that coordinates idle detection across multiple browser tabs
- **Navbar**: The top navigation bar rendered by the `navbar` Thymeleaf fragment

## Requirements

### Requirement 1: Password Reset Page

**User Story:** As a user who forgot my password, I want a page where I can enter my reset token and a new password, so that I can regain access to my account.

#### Acceptance Criteria

1. WHEN a user navigates to `/reset-password`, THE Reset_Page SHALL render a form with a token input field, a new password field, a confirm password field, and a submit button
2. WHEN the `token` query parameter is present in the URL, THE Reset_Page SHALL pre-fill the token input field with the query parameter value
3. WHEN the user submits the form with matching passwords, THE Reset_Page SHALL send a POST request to the `/api/password/reset` endpoint with the token and new password
4. WHEN the password reset API returns a success response, THE Reset_Page SHALL display a success message and provide a link to the login page
5. IF the password reset API returns an error response, THEN THE Reset_Page SHALL display the error message to the user without clearing the form fields
6. IF the new password and confirm password fields do not match, THEN THE Reset_Page SHALL display a client-side validation error and prevent form submission
7. WHEN the `AuthWebController` serves the `/reset-password` route, THE Application SHALL return the reset password Thymeleaf template

### Requirement 2: Room Edit and Update

**User Story:** As a room owner, I want to edit my room's name, description, and visibility after creation, so that I can keep room details current.

#### Acceptance Criteria

1. THE Room_API SHALL expose a `PUT /api/rooms/{id}` endpoint that accepts a JSON body with optional `name`, `description`, and `visibility` fields
2. WHEN a `PUT /api/rooms/{id}` request is received from the room owner, THE Room_API SHALL update the room fields and return the updated room DTO with HTTP 200
3. IF a `PUT /api/rooms/{id}` request is received from a user who is not the room owner, THEN THE Room_API SHALL return HTTP 403
4. IF the updated room name conflicts with an existing room name, THEN THE Room_API SHALL return HTTP 409 with a descriptive error message
5. WHEN the room owner views the room, THE Room_Settings_UI SHALL display a settings button that opens a modal with pre-filled name, description, and visibility fields
6. WHEN the room owner submits the settings modal form, THE Room_Settings_UI SHALL send a PUT request to `/api/rooms/{id}` and update the displayed room details on success
7. IF the settings update API returns an error, THEN THE Room_Settings_UI SHALL display the error message within the modal

### Requirement 3: Friend Request Text Passthrough

**User Story:** As a user sending a friend request, I want my optional message to be delivered to the recipient, so that they understand why I am requesting friendship.

#### Acceptance Criteria

1. WHEN the `FriendshipApiController.sendFriendRequest` method receives a request body, THE Friend_Request_API SHALL pass the `requestText` field from the request body to the `FriendshipService.sendFriendRequest` method instead of hardcoding null
2. THE `SendFriendRequest` record SHALL include an optional `requestText` field
3. WHEN a friend request is sent without a `requestText` value, THE Friend_Request_API SHALL pass null to the service method

### Requirement 4: Sidebar Population

**User Story:** As a logged-in user, I want the sidebar to show my rooms and contacts with unread counts and presence indicators, so that I can navigate the application and see activity at a glance.

#### Acceptance Criteria

1. WHEN the `/chat` or `/chat/rooms/{id}` page loads, THE Sidebar SHALL fetch the current user's joined rooms from the API and populate the public rooms, private rooms, and direct messages accordion sections
2. WHEN rooms are populated in the Sidebar, THE Sidebar SHALL display each room name as a clickable link to `/chat/rooms/{id}`
3. WHEN rooms are populated in the Sidebar, THE Sidebar SHALL display unread message count badges next to rooms with unread messages
4. WHEN the `/chat` or `/chat/rooms/{id}` page loads, THE Sidebar SHALL fetch the current user's accepted friends from the API and populate the contacts section
5. WHEN contacts are populated in the Sidebar, THE Sidebar SHALL display a presence dot (green for ONLINE, yellow for AFK, grey for OFFLINE) next to each contact name
6. WHEN a real-time unread count notification is received via WebSocket, THE Sidebar SHALL update the corresponding room's unread badge without a full page reload

### Requirement 5: Emoji Picker

**User Story:** As a user composing a message, I want to pick emojis from a popup grid, so that I can insert emoji characters into my messages without memorizing codes.

#### Acceptance Criteria

1. WHEN the user clicks the 😊 button in the message input area, THE Emoji_Picker SHALL display a popup grid of common emoji characters
2. WHEN the user selects an emoji from the grid, THE Emoji_Picker SHALL insert the selected emoji character at the current cursor position in the message textarea
3. WHEN the user selects an emoji, THE Emoji_Picker SHALL close the popup after insertion
4. WHEN the user clicks outside the Emoji_Picker popup, THE Emoji_Picker SHALL close the popup
5. THE Emoji_Picker SHALL be implemented using inline HTML and vanilla JavaScript without external emoji libraries

### Requirement 6: Typing Indicators

**User Story:** As a user in a chat room, I want to see when other users are typing, so that I know a response is being composed.

#### Acceptance Criteria

1. WHEN the user types in the message textarea within a room, THE Application SHALL send a typing event to the `/app/typing` STOMP destination with the current room ID
2. THE Application SHALL debounce typing events so that consecutive keystrokes within 2 seconds produce only one typing event
3. WHEN a typing event is received via the `/topic/room.{roomId}.events` subscription, THE Typing_Indicator SHALL display "{username} is typing..." below the message list
4. WHEN multiple users are typing simultaneously, THE Typing_Indicator SHALL display all typing usernames (e.g., "Alice and Bob are typing...")
5. WHEN no new typing event is received from a user within 3 seconds, THE Typing_Indicator SHALL remove that user from the typing display
6. THE Typing_Indicator SHALL not display a typing notification for the current user's own typing events

### Requirement 7: Message Edit UI

**User Story:** As a message author, I want to edit my sent messages inline, so that I can correct mistakes without deleting and resending.

#### Acceptance Criteria

1. WHEN a message is rendered that was sent by the current user, THE Application SHALL display an edit button alongside the message actions
2. WHEN the user clicks the edit button, THE Message_Edit_UI SHALL replace the message text with an editable textarea pre-filled with the current message content and display save and cancel buttons
3. WHEN the user clicks save, THE Message_Edit_UI SHALL send a PUT request to `/api/rooms/{roomId}/messages/{messageId}` with the updated content
4. WHEN the edit API returns success, THE Message_Edit_UI SHALL replace the textarea with the updated message text and display an "(edited)" indicator
5. WHEN the user clicks cancel, THE Message_Edit_UI SHALL restore the original message text and remove the editing controls
6. IF the edit API returns an error, THEN THE Message_Edit_UI SHALL display the error message and keep the textarea open for correction

### Requirement 8: Message Delete Button Wiring

**User Story:** As a message author, I want the delete button on my messages to work, so that I can remove messages I no longer want visible.

#### Acceptance Criteria

1. WHEN the user clicks the 🗑 button on their own message, THE Message_Delete_Handler SHALL display a confirmation prompt before proceeding
2. WHEN the user confirms deletion, THE Message_Delete_Handler SHALL send a DELETE request to `/api/rooms/{roomId}/messages/{messageId}`
3. WHEN the delete API returns HTTP 204, THE Message_Delete_Handler SHALL remove the message element from the DOM
4. IF the delete API returns an error, THEN THE Message_Delete_Handler SHALL display the error message using the error modal

### Requirement 9: Banned Users Modal

**User Story:** As a room admin, I want to view banned users and unban them, so that I can manage room access.

#### Acceptance Criteria

1. WHEN the banned users modal is opened, THE Banned_Users_Modal SHALL fetch the list of banned users from `GET /api/rooms/{roomId}/bans` and render each entry with the banned username, the banning admin's username, and the ban date
2. WHEN the banned users list is empty, THE Banned_Users_Modal SHALL display a "No banned users" message
3. WHEN the admin clicks an unban button next to a banned user, THE Banned_Users_Modal SHALL send a DELETE request to `/api/rooms/{roomId}/bans/{userId}`
4. WHEN the unban API returns HTTP 204, THE Banned_Users_Modal SHALL remove the unbanned user entry from the list
5. IF the unban API returns an error, THEN THE Banned_Users_Modal SHALL display the error message within the modal

### Requirement 10: Profile Page Form Submission

**User Story:** As a logged-in user, I want the profile page forms to submit my changes, so that I can update my display name, change my password, and delete my account.

#### Acceptance Criteria

1. WHEN the user submits the display name form, THE Profile_Forms_JS SHALL send a PUT request to `/api/users/me` with the new display name and display a success or error message
2. WHEN the user submits the change password form, THE Profile_Forms_JS SHALL validate that the new password and confirm password fields match before sending a POST request to `/api/password/change`
3. IF the new password and confirm password do not match, THEN THE Profile_Forms_JS SHALL display a client-side validation error without submitting the form
4. WHEN the password change API returns success, THE Profile_Forms_JS SHALL display a success message and clear the password fields
5. IF the password change API returns an error, THEN THE Profile_Forms_JS SHALL display the error message
6. WHEN the user clicks the delete account button, THE Profile_Forms_JS SHALL display a confirmation dialog before sending a DELETE request to `/api/users/me`
7. WHEN the account deletion API returns HTTP 204, THE Profile_Forms_JS SHALL redirect the user to the login page

### Requirement 11: Sidebar Search Filtering

**User Story:** As a user, I want to filter rooms and contacts by typing in the sidebar search box, so that I can quickly find a specific room or contact.

#### Acceptance Criteria

1. WHEN the user types in the sidebar search input, THE Sidebar_Search SHALL filter the displayed rooms and contacts to show only entries whose names contain the search text (case-insensitive)
2. WHEN the search input is cleared, THE Sidebar_Search SHALL restore all rooms and contacts to their original visibility
3. THE Sidebar_Search SHALL apply filtering in real time as the user types, without requiring a submit action

### Requirement 12: Add Friend from Room Member List

**User Story:** As a room member, I want to send a friend request to another member from the member list dropdown, so that I can connect with people I chat with.

#### Acceptance Criteria

1. WHEN the member list dropdown is rendered for a member who is not the current user, THE Application SHALL include an "Add friend" option in the dropdown menu
2. WHEN the user clicks the "Add friend" option, THE Friend_Request_Option SHALL send a POST request to `/api/friends/request` with the target member's username
3. WHEN the friend request API returns HTTP 201, THE Friend_Request_Option SHALL display a success feedback message
4. IF the friend request API returns an error (e.g., friendship already exists, user ban), THEN THE Friend_Request_Option SHALL display the error message

### Requirement 13: Friend Request Management UI

**User Story:** As a user, I want to see my pending friend requests and accept or decline them, so that I can manage my social connections.

#### Acceptance Criteria

1. THE Application SHALL provide a friend requests section accessible from the contacts area in the sidebar or as a dedicated panel
2. WHEN the friend requests section is opened, THE Friend_Request_Panel SHALL fetch pending incoming requests from `GET /api/friends/requests/incoming` and display each with the requester's username and optional request text
3. WHEN the friend requests section is opened, THE Friend_Request_Panel SHALL fetch pending outgoing requests from `GET /api/friends/requests/outgoing` and display each with the recipient's username
4. WHEN the user clicks accept on an incoming request, THE Friend_Request_Panel SHALL send a POST request to `/api/friends/{id}/accept` and move the entry to the contacts list on success
5. WHEN the user clicks decline on an incoming request, THE Friend_Request_Panel SHALL send a POST request to `/api/friends/{id}/decline` and remove the entry from the list on success
6. IF the accept or decline API returns an error, THEN THE Friend_Request_Panel SHALL display the error message
7. THE Friend_Request_API SHALL expose `GET /api/friends/requests/incoming` and `GET /api/friends/requests/outgoing` endpoints that return pending friendship records for the current user

### Requirement 14: DM Initiation from Contacts

**User Story:** As a user, I want to click a contact in the sidebar to open a direct message conversation, so that I can quickly start chatting with friends.

#### Acceptance Criteria

1. WHEN contacts are rendered in the Sidebar, THE Contact_DM_Link SHALL make each contact name a clickable element
2. WHEN the user clicks a contact name, THE Contact_DM_Link SHALL send a POST request to `/api/direct-chats` with the contact's user ID to get or create a direct chat room
3. WHEN the direct chat API returns a room, THE Contact_DM_Link SHALL navigate the browser to `/chat/rooms/{roomId}` for the returned room

### Requirement 15: Attachment Rendering in Messages

**User Story:** As a user viewing messages, I want uploaded images to display inline and other files to show as download links, so that I can view and access shared files directly in the chat.

#### Acceptance Criteria

1. WHEN a message contains an image attachment, THE Attachment_Renderer SHALL display the image inline as a thumbnail with a maximum width of 400 pixels
2. WHEN the user clicks an inline image thumbnail, THE Attachment_Renderer SHALL open the full-size image in a new browser tab or a lightbox overlay
3. WHEN a message contains a non-image attachment, THE Attachment_Renderer SHALL display the original filename as a clickable download link pointing to the attachment download API endpoint
4. WHEN rendering attachments, THE Attachment_Renderer SHALL use the attachment metadata (content type, original filename, attachment ID) from the message or a supplementary API call
5. THE Application SHALL include attachment metadata in the message response DTO so that the Attachment_Renderer can distinguish image from non-image files

### Requirement 16: Leave Room Button

**User Story:** As a room member, I want a button to leave a room, so that I can remove myself from rooms I no longer wish to participate in.

#### Acceptance Criteria

1. WHEN the current user is a member of a room and is not the room owner, THE Leave_Room_Button SHALL be displayed in the room member panel
2. WHEN the user clicks the Leave_Room_Button, THE Application SHALL display a confirmation prompt before proceeding
3. WHEN the user confirms leaving, THE Application SHALL send a POST request to `/api/rooms/{id}/leave`
4. WHEN the leave API returns success, THE Application SHALL redirect the user to the `/chat` page
5. THE Leave_Room_Button SHALL not be displayed for the room owner

### Requirement 17: AFK Multi-Tab Coordination

**User Story:** As a user with multiple tabs open, I want my presence status to reflect AFK only when all tabs are idle for 1 minute or more, so that my status is accurate.

#### Acceptance Criteria

1. THE AFK_Coordinator SHALL use the BroadcastChannel API (with localStorage fallback for browsers that do not support BroadcastChannel) to share activity state across tabs
2. WHEN any tab detects user activity (mouse movement or keyboard input), THE AFK_Coordinator SHALL broadcast an "active" signal to all other tabs
3. WHEN a tab sends a presence heartbeat, THE AFK_Coordinator SHALL report `active: true` if any tab (including itself) has detected user activity within the last 2 seconds
4. WHEN all tabs have been idle for 60 seconds or more, THE AFK_Coordinator SHALL report `active: false` in the next heartbeat
5. WHEN a tab receives focus or the document becomes visible (via the `visibilitychange` event or `focus` event) after being idle, THE AFK_Coordinator SHALL immediately send a presence heartbeat with `active: true` without waiting for the next scheduled heartbeat interval, so that the user's ONLINE status is restored with minimal latency
6. WHEN a tab is closed, THE AFK_Coordinator SHALL clean up its BroadcastChannel listener and localStorage entries

### Requirement 18: Navbar Link Corrections

**User Story:** As a user, I want the navbar links to navigate to the correct pages, so that I can access public rooms, private rooms, contacts, and sessions without confusion.

#### Acceptance Criteria

1. THE Navbar "Public Rooms" link SHALL navigate to `/rooms/catalog` (the public room browsing page)
2. THE Navbar "Private Rooms" link SHALL navigate to `/chat` with the private rooms accordion section expanded
3. THE Navbar "Contacts" link SHALL navigate to `/chat` with the contacts section visible
4. THE Navbar "Sessions" link SHALL navigate to `/profile/sessions` instead of `/sessions`
