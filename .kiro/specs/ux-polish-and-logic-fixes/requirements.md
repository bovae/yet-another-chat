# Requirements Document

## Introduction

This specification covers 19 discrete UI/UX improvements and backend logic fixes for the YAC chat application. These items address broken flows (password reset, presence, unread badges), missing UI elements (reply button, room invitation panel, user block UI), incorrect access control (admin actions visible to all, DIRECT visibility leaks), and data integrity issues (nextWatermark not initialized on DM creation). All work targets the existing Spring Boot 4 + Thymeleaf + Bootstrap 5.3 + STOMP.js + vanilla JS stack.

## Glossary

- **Application**: The YAC Spring Boot web application as a whole
- **Forgot_Password_Page**: The Thymeleaf-rendered page at `/forgot-password` where a user submits their email to receive a reset link
- **Auth_Web_Controller**: The Spring MVC controller (`AuthWebController`) that handles authentication-related web routes
- **Password_Service**: The service (`PasswordService`) responsible for creating and validating password reset tokens
- **Navbar**: The top navigation bar rendered by the `navbar` Thymeleaf fragment, displayed on all authenticated pages
- **Navbar_Advice**: A `@ControllerAdvice` or `@ModelAttribute` mechanism that injects the current user object into all authenticated views
- **Sidebar**: The left-hand navigation panel rendered by the `sidebar` Thymeleaf fragment, displaying rooms and contacts
- **Direct_Chat_Service**: The service (`DirectChatService`) responsible for creating and listing direct message rooms
- **My_Room_Entry**: The DTO (`MyRoomEntry`) returned by `GET /api/rooms/my` representing a user's joined room
- **Presence_Service**: The service (`PresenceService`) that tracks user online/AFK/offline status via Redis
- **Presence_API**: A REST endpoint that returns current presence status for a batch of user IDs
- **Room_Service**: The service (`RoomService`) responsible for room CRUD operations including visibility changes
- **Room_API**: The REST controller at `/api/rooms` responsible for room CRUD operations
- **Room_Settings_Modal**: The Bootstrap modal in `admin-modals.html` that allows editing room name, description, and visibility
- **Member_List**: The right-hand panel rendered by the `member-list` Thymeleaf fragment showing room members and action buttons
- **Chat_Web_Controller**: The Spring MVC controller (`ChatWebController`) that renders the room view
- **Notification_Service**: The service (`NotificationService`) responsible for unread count computation and broadcast
- **Chat_Message_Handler**: The WebSocket STOMP handler (`ChatMessageHandler`) that processes and broadcasts chat messages
- **Room_Member_Service**: The service (`RoomMemberService`) that manages room membership
- **Message_Input**: The message composition area rendered by the `message-input` Thymeleaf fragment
- **Room_View_Template**: The Thymeleaf template (`chat/room.html`) that renders the room view with server-rendered messages
- **Room_Invitation_Panel**: A UI section that displays pending room invitations with accept/decline actions
- **Room_Invitation_API**: The REST controller (`RoomInvitationApiController`) that handles room invitation CRUD
- **User_Ban_API**: The REST controller (`UserBanApiController`) that handles user-to-user blocking
- **Attachment_API**: The REST controller (`AttachmentApiController`) that handles file upload and download
- **Reply_Button**: A UI button on each message that initiates the reply flow
- **Saved_Messages**: A special DIRECT room where a user can store personal notes, functioning as a self-DM
- **Message_Bubble**: A styled container for message content that visually distinguishes own messages (right-aligned) from others' messages (left-aligned)

## Requirements

### Requirement 1: Password Reset Link Display on Forgot Password Page

**User Story:** As a user who forgot my password, I want to see the generated reset link directly on the forgot-password page after submitting my email, so that I can reset my password without needing email delivery (development mode).

#### Acceptance Criteria

1. WHEN a user submits a valid email on the Forgot_Password_Page and the email corresponds to an existing account, THE Auth_Web_Controller SHALL call `Password_Service.createResetToken()` and capture the returned raw token
2. WHEN the raw token is captured, THE Forgot_Password_Page SHALL display the full reset link (e.g., `/reset-password?token={rawToken}`) directly on the page as a clickable hyperlink
3. WHEN the submitted email does not correspond to any account, THE Forgot_Password_Page SHALL display a generic success message without revealing whether the account exists
4. THE Auth_Web_Controller SHALL pass the generated reset link to the Thymeleaf model so the template can render it conditionally

### Requirement 2: Navbar Username and Avatar Dropdown

**User Story:** As a logged-in user, I want to see my username and avatar in the navbar with a dropdown menu for Profile, Sessions, and Sign out, so that I have quick access to account actions from any page.

#### Acceptance Criteria

1. THE Navbar SHALL display the current user's username and an avatar circle (first letter of username, styled identically to message avatars) on the right side of the navigation bar
2. THE Navbar SHALL display a dropdown arrow next to the avatar that opens a menu containing: (1) Profile link, (2) Sessions link, (3) Sign out action
3. WHEN the user clicks "Profile" in the dropdown, THE Application SHALL navigate to `/profile`
4. WHEN the user clicks "Sessions" in the dropdown, THE Application SHALL navigate to `/profile/sessions`
5. WHEN the user clicks "Sign out" in the dropdown, THE Application SHALL submit a POST to `/logout`
6. THE Application SHALL provide the current authenticated user object to the Navbar fragment on all authenticated pages via a `@ControllerAdvice` or `@ModelAttribute` mechanism (Navbar_Advice)
7. THE Navbar SHALL remove the existing flat "Sign out" button and replace it with the avatar dropdown

### Requirement 3: DM Sidebar Display with Other User's Name

**User Story:** As a user viewing my direct messages in the sidebar, I want to see the other participant's username instead of the internal room name (e.g., "dm-xxx-xxx"), so that I can identify my conversations.

#### Acceptance Criteria

1. WHEN the Sidebar populates direct message entries, THE Sidebar SHALL display the other participant's username or display name instead of the raw room name for DIRECT visibility rooms
2. THE My_Room_Entry DTO SHALL include optional fields (`otherUsername` and `otherDisplayName`) that are populated only for DIRECT visibility rooms
3. WHEN `Room_Service.listUserRoomsWithUnread()` builds entries for DIRECT rooms, THE Room_Service SHALL resolve the other participant's username and display name by querying room members
4. WHEN the other participant has a display name set, THE Sidebar SHALL prefer displaying the display name over the username

### Requirement 4: Initial Presence Status Fetch

**User Story:** As a user viewing contacts or room members, I want to see their current online status immediately on page load, so that presence dots are accurate from the start rather than always showing gray.

#### Acceptance Criteria

1. THE Application SHALL expose a `GET /api/presence` endpoint that accepts a `userIds` query parameter (comma-separated list of UUIDs) and returns the current `PresenceStatus` for each requested user
2. WHEN the Sidebar finishes populating contacts, THE Application SHALL call `GET /api/presence?userIds=...` with all visible user IDs and update presence dots accordingly
3. WHEN the room member list is rendered, THE Application SHALL call `GET /api/presence?userIds=...` with all member user IDs and update presence dots accordingly
4. THE Presence_API SHALL delegate to `Presence_Service.getUserStatus(userId)` for each requested user ID
5. THE Presence_API SHALL return a JSON array of objects containing `userId` and `status` fields

### Requirement 5: Prevent DIRECT Visibility Conversion

**User Story:** As a system administrator, I want the application to prevent any room from being changed to or from DIRECT visibility, so that the DIRECT room invariant is maintained.

#### Acceptance Criteria

1. WHEN a `PUT /api/rooms/{id}` request attempts to change the visibility of a DIRECT room, THE Room_Service SHALL reject the request with HTTP 403 and a descriptive error message
2. WHEN a `PUT /api/rooms/{id}` request attempts to set any room's visibility to DIRECT, THE Room_Service SHALL reject the request with HTTP 403 and a descriptive error message
3. THE Room_Settings_Modal SHALL exclude the DIRECT option from the visibility dropdown by filtering `RoomVisibility.values()` to only show PUBLIC and PRIVATE
4. WHEN a `POST /api/rooms` request specifies DIRECT visibility, THE Room_API SHALL reject the request with HTTP 400 and a descriptive error message

### Requirement 6: Saved Messages (Self-DM as Personal Notes)

**User Story:** As a user, I want a "Saved Messages" chat where I can store personal notes and links, functioning as a self-DM, so that I have a private space for quick notes within the chat application.

#### Acceptance Criteria

1. THE Direct_Chat_Service SHALL allow creating a direct chat room where both participants are the same user (self-DM), removing the existing self-check that throws ForbiddenException
2. WHEN a self-DM room is created, THE Direct_Chat_Service SHALL create a DIRECT room with a single RoomMember entry for the user
3. THE Direct_Chat_Service SHALL provide a `getOrCreateSavedMessages(User)` method that creates or retrieves the user's self-DM room
4. WHEN the Sidebar renders the direct messages section, THE Sidebar SHALL display the self-DM room as "Saved Messages" with a bookmark icon (🔖) instead of an avatar
5. THE `listDirectChats()` method SHALL handle the self-DM case where there is no "other user" by returning a special entry with the current user's own info
6. THE `MessageService.checkDirectChatBan()` SHALL skip the ban check for self-DM rooms (rooms where the only member is the sender)
7. THE Application SHALL provide a quick-access link or button in the sidebar to open or create the Saved Messages room

### Requirement 7: Restrict Admin Actions to Admins and Owners

**User Story:** As a room member, I want admin actions (kick, ban, promote, demote) to only be visible to admins and owners, so that regular members do not see actions they cannot perform.

#### Acceptance Criteria

1. THE Member_List SHALL display the admin actions dropdown (kick, ban, promote, demote) only for members whose role is OWNER or ADMIN in the current room
2. THE Member_List SHALL hide the admin actions dropdown for members whose role is MEMBER
3. THE Member_List SHALL display the "Invite user" button only for members whose role is OWNER or ADMIN
4. THE Member_List SHALL display the "View banned users" button only for members whose role is OWNER or ADMIN
5. THE Room_View_Template SHALL pass the current user's room role to the member-list fragment so that visibility conditions can be evaluated

### Requirement 8: Clear Unread Badges on Room View

**User Story:** As a user opening a chat room, I want my unread count for that room to be cleared, so that the sidebar badge accurately reflects messages I have not yet seen.

#### Acceptance Criteria

1. WHEN the Chat_Web_Controller renders the room view for an authenticated member, THE Chat_Web_Controller SHALL call `Notification_Service.markRoomAsRead(user, room)` to reset the unread count
2. WHEN `markRoomAsRead` is called, THE Notification_Service SHALL update the user's `UnreadMarker` for that room to the current watermark
3. THE Application SHALL only call `markRoomAsRead` when the user is a member of the room

### Requirement 9: Real-Time Unread Count Broadcast on New Message

**User Story:** As a user in multiple rooms, I want my sidebar unread badges to update in real-time when new messages arrive in rooms I am not currently viewing, so that I can see activity without refreshing.

#### Acceptance Criteria

1. WHEN the Chat_Message_Handler broadcasts a new message to a room, THE Chat_Message_Handler SHALL compute updated unread counts for all other room members who are not currently viewing that room
2. WHEN unread counts are computed, THE Chat_Message_Handler SHALL broadcast a notification event to each affected member via their personal WebSocket queue (`/user/{email}/queue/notifications`)
3. THE notification event SHALL include the room ID and the updated unread count for the recipient
4. THE Sidebar SHALL update the corresponding room's unread badge when a notification event is received via WebSocket

### Requirement 10: Reply Button on Messages

**User Story:** As a user reading messages, I want a reply button on each message so that I can initiate a threaded reply, since the reply infrastructure exists but has no trigger.

#### Acceptance Criteria

1. WHEN a message is rendered via JavaScript (WebSocket-delivered), THE Application SHALL display a reply button (↩) in the message actions area
2. WHEN a message is rendered via Thymeleaf (server-rendered on page load), THE Room_View_Template SHALL display a reply button (↩) in the message actions area
3. WHEN the user clicks the reply button, THE Application SHALL populate the hidden `#reply-to-id` input with the message ID, show the reply indicator with the original message author's name, and focus the message textarea
4. THE reply button SHALL be visible on all messages regardless of authorship

### Requirement 11: Server-Rendered Messages Feature Parity

**User Story:** As a user viewing messages loaded on initial page load, I want to see edit buttons, attachments, and admin delete buttons, so that server-rendered messages have the same functionality as WebSocket-delivered messages.

#### Acceptance Criteria

1. WHEN a server-rendered message belongs to the current user, THE Room_View_Template SHALL display an edit button (✏️) alongside the existing delete button (🗑)
2. WHEN a server-rendered message has attachments, THE Room_View_Template SHALL render image attachments inline (with max-width 400px) and non-image attachments as download links with the original filename
3. WHEN the current user has OWNER or ADMIN role in the room, THE Room_View_Template SHALL display a delete button (🗑) on messages from other users
4. THE Chat_Web_Controller SHALL pass the current user's room role to the template model so that role-based rendering conditions can be evaluated
5. THE message history query SHALL include attachment metadata for each message so that the template can render attachments

### Requirement 12: Room Invitation Acceptance UI

**User Story:** As a user who has been invited to a room, I want to see my pending room invitations and accept or decline them, so that I can join rooms I have been invited to.

#### Acceptance Criteria

1. THE Application SHALL expose a `GET /api/rooms/invitations/pending` endpoint that returns all pending room invitations for the current user
2. THE Sidebar SHALL display a "Room Invitations" section (or badge indicator) when the user has pending room invitations
3. WHEN the user views pending room invitations, THE Room_Invitation_Panel SHALL display each invitation with the room name, inviter's username, and accept/decline buttons
4. WHEN the user clicks accept, THE Room_Invitation_Panel SHALL send a POST request to `/api/rooms/{roomId}/invitations/{id}/accept` and navigate to the room on success
5. WHEN the user clicks decline, THE Room_Invitation_Panel SHALL send a DELETE request to `/api/rooms/{roomId}/invitations/{id}` and remove the entry from the list
6. IF the accept or decline API returns an error, THEN THE Room_Invitation_Panel SHALL display the error message

### Requirement 13: User-to-User Block UI

**User Story:** As a user, I want to block another user from the member list or contact list, so that I can prevent unwanted interactions.

#### Acceptance Criteria

1. THE Member_List dropdown SHALL include a "Block user" option for members who are not the current user
2. WHEN the user clicks "Block user" in the member list dropdown, THE Application SHALL display a confirmation prompt before proceeding
3. WHEN the user confirms the block action, THE Application SHALL send a POST request to `/api/user-bans` with the target user's ID
4. WHEN the block API returns HTTP 201, THE Application SHALL display a success feedback message
5. IF the block API returns an error, THEN THE Application SHALL display the error message using the error modal

### Requirement 14: Reply Quote Shows Original Message Content

**User Story:** As a user viewing a reply, I want to see the original message's author and a content snippet in the reply quote, so that I understand the context of the reply.

#### Acceptance Criteria

1. WHEN a message with a `replyToId` is rendered via JavaScript, THE Application SHALL fetch or use cached data to display the replied-to message's author name and a content snippet (first 100 characters) in the reply quote
2. WHEN a message with a `replyToId` is rendered via Thymeleaf, THE Room_View_Template SHALL display the replied-to message's author name and content snippet in the reply quote
3. THE message response DTO SHALL include optional fields for the replied-to message's sender username and content snippet so that the frontend does not need a separate fetch
4. IF the replied-to message has been deleted, THEN THE Application SHALL display "Original message deleted" in the reply quote

### Requirement 15: Prevent DIRECT Room Creation via API

**User Story:** As a system, I want to reject attempts to create DIRECT rooms through the general room creation API, so that DIRECT rooms are only created through the proper DM flow.

#### Acceptance Criteria

1. WHEN a `POST /api/rooms` request specifies `DIRECT` as the visibility value, THE Room_API SHALL reject the request with HTTP 400 and a message indicating that DIRECT rooms cannot be created through this endpoint
2. THE validation SHALL occur before any room creation logic is executed

### Requirement 16: Initialize nextWatermark on DM Creation

**User Story:** As a system, I want DM rooms to have `nextWatermark` initialized to 1 on creation, so that message ordering and unread count computation work correctly from the first message.

#### Acceptance Criteria

1. WHEN the Direct_Chat_Service creates a new direct chat room, THE Direct_Chat_Service SHALL set `nextWatermark` to `1L` on the Room entity before persisting it
2. THE Room entity created by the DM flow SHALL have the same `nextWatermark` initial value as rooms created through `Room_Service.createRoom()`

### Requirement 17: Inline Image Display for Attachments

**User Story:** As a user viewing image attachments in messages, I want images to display inline rather than triggering a download, so that I can preview images directly in the chat.

#### Acceptance Criteria

1. WHEN the Attachment_API serves a download request for an attachment whose content type starts with `image/`, THE Attachment_API SHALL set the `Content-Disposition` header to `inline` instead of `attachment`
2. WHEN the Attachment_API serves a download request for a non-image attachment, THE Attachment_API SHALL retain the `Content-Disposition: attachment` header to trigger a file download
3. THE Application SHALL determine the disposition based on the attachment's stored `contentType` field

### Requirement 18: Modern Messenger-Style Message Layout (Left/Right Alignment)

**User Story:** As a user chatting in a room, I want my own messages displayed on the right side and other users' messages on the left side with chat bubbles, so that the conversation feels like a modern messenger.

#### Acceptance Criteria

1. WHEN a message is from another user, THE Application SHALL render it left-aligned with the avatar on the left side of the message bubble
2. WHEN a message is from the current user, THE Application SHALL render it right-aligned with the avatar on the right side of the message bubble
3. THE message bubble for own messages SHALL have a distinct background color (e.g., light blue) to differentiate from others' messages (e.g., white/light gray)
4. THE username and timestamp SHALL be displayed above the message bubble in a smaller, muted style
5. FOR own messages, THE username and timestamp SHALL be right-aligned above the bubble
6. FOR other users' messages, THE username and timestamp SHALL be left-aligned above the bubble
7. THE message actions (edit, delete, reply) SHALL appear on hover near the message bubble, positioned appropriately for left-aligned and right-aligned messages
8. THE layout changes SHALL apply consistently to both server-rendered (Thymeleaf) and JavaScript-rendered (WebSocket) messages
9. THE layout SHALL use CSS classes (e.g., `message-own`, `message-other`) to control alignment, with the determination based on comparing `senderId` to the current user's ID

### Requirement 19: Message Bubble Visual Design

**User Story:** As a user, I want messages to appear in styled bubbles with rounded corners and appropriate spacing, so that the chat interface looks polished and modern.

#### Acceptance Criteria

1. THE message content (text, attachments, reply quotes) SHALL be wrapped in a bubble container with rounded corners, padding, and a subtle shadow or border
2. THE bubble for own messages SHALL have a background color of `#d1ecf1` (light blue) or similar distinguishing color
3. THE bubble for other users' messages SHALL have a background color of `#ffffff` (white) with a light border
4. THE avatar SHALL be 36px circular with the first letter of the username, positioned outside the bubble
5. THE maximum width of a message bubble SHALL be approximately 70% of the message area width to prevent full-width stretching
6. THE message actions (edit, delete, reply) SHALL be hidden by default and appear on hover over the message item, styled as small icon buttons
