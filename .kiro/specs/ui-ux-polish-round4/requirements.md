# Requirements Document

## Introduction

This specification covers a set of UI/UX polish improvements and a real-time message deletion bug fix for the YAC (Yet Another Chat) application. The changes target the chat room view layout, sidebar organization, message input sizing, and WebSocket-based message deletion propagation.

## Glossary

- **Chat_Header**: The header bar above the message area displaying room name and member count
- **Left_Sidebar**: The left panel (260px) containing room lists, contacts, and action buttons
- **Right_Panel**: The right panel (220px) displaying room info and member list
- **Message_Input**: The text input area at the bottom of the chat for composing messages
- **Message_Item**: A single rendered message element in the message list
- **Accordion_Section**: A collapsible section in the left sidebar (Public Rooms, Private Rooms, Direct Messages)
- **STOMP_Client**: The WebSocket STOMP client handling real-time message delivery
- **Non_Owner_User**: A room member whose role is MEMBER (not OWNER or ADMIN)
- **More_Menu**: A dropdown menu providing social actions (block user, add friend) for non-admin members

## Requirements

### Requirement 1: Remove Room Description from Chat Header

**User Story:** As a chat user, I want to see only the room name in the chat header above the message area, so that the header is cleaner and less cluttered.

#### Acceptance Criteria

1. THE Chat_Header SHALL display only the room name and member count badge
2. THE Chat_Header SHALL NOT display the room description text
3. THE Right_Panel SHALL continue to display the room name and description under "Room info" without any changes

### Requirement 2: Remove Hover Highlight on Chat Messages

**User Story:** As a chat user, I want message lines to not change background color when I hover over them, so that the chat area feels less visually noisy.

#### Acceptance Criteria

1. THE Message_Item SHALL NOT change its background color when the cursor hovers over it
2. THE Message_Item SHALL continue to show message action buttons (reply, edit, delete) on hover of the message bubble

### Requirement 3: Separate Header Section for Sidebar Action Buttons

**User Story:** As a chat user, I want the "Create room" and "Browse" buttons to be in their own labeled section in the left sidebar, so that they are visually distinct from the room lists and contacts.

#### Acceptance Criteria

1. THE Left_Sidebar SHALL display the "Create room" and "Browse" buttons in a dedicated section with a visible header label
2. THE Left_Sidebar SHALL visually separate the action buttons section from the Contacts section using a horizontal rule or equivalent separator
3. THE Left_Sidebar SHALL position the action buttons section below the Contacts section

### Requirement 4: Expand All Sidebar Accordion Sections by Default

**User Story:** As a chat user, I want all room list sections in the left sidebar to be expanded when I open the chat, so that I can immediately see all my rooms without clicking to expand each section.

#### Acceptance Criteria

1. WHEN the chat page loads, THE Left_Sidebar SHALL display the Public Rooms accordion section in expanded state
2. WHEN the chat page loads, THE Left_Sidebar SHALL display the Private Rooms accordion section in expanded state
3. WHEN the chat page loads, THE Left_Sidebar SHALL display the Direct Messages accordion section in expanded state

### Requirement 5: Larger Message Input Area

**User Story:** As a chat user, I want the message input area to be slightly larger, so that I have more space to compose messages comfortably.

#### Acceptance Criteria

1. THE Message_Input textarea SHALL render with a default height of 2 rows instead of 1 row
2. THE Message_Input section SHALL have increased vertical padding to provide more breathing room around the input controls

### Requirement 6: More Menu for Non-Owner Members in Left Sidebar

**User Story:** As a non-admin room member, I want a "more" menu available in the left sidebar that lets me block a user or add a friend, so that I can manage social interactions without needing admin privileges.

#### Acceptance Criteria

1. WHEN a Non_Owner_User views the left sidebar, THE Left_Sidebar SHALL display a "More" dropdown menu
2. WHEN the Non_Owner_User clicks the "More" menu, THE Left_Sidebar SHALL show options to "Block user" and "Add friend"
3. WHILE the current user has the OWNER or ADMIN role, THE Left_Sidebar SHALL NOT display the "More" menu (these users already have admin actions in the right panel)

### Requirement 7: Real-Time Message Deletion for Current User

**User Story:** As a chat user, I want a message I delete to immediately disappear from my view without requiring a page refresh, so that the deletion feels responsive and confirmed.

#### Acceptance Criteria

1. WHEN a user deletes a message via the delete button, THE Message_Item SHALL be removed from the DOM immediately after the server confirms deletion (HTTP 204 response)
2. WHEN a message is deleted by any room member, THE STOMP_Client SHALL broadcast a deletion event to all connected room subscribers
3. WHEN a deletion event is received via WebSocket, THE Message_Item corresponding to the deleted message SHALL be removed from the DOM for all connected users in the room
4. IF the delete API call fails, THEN THE Message_Item SHALL remain in the DOM and an error modal SHALL be displayed to the user
