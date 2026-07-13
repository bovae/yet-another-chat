# Requirements Document

## Introduction

Yet Another Chat (YAC) is a classic web-based online chat application built with Spring Boot 4, Thymeleaf, HTMX, STOMP/WebSocket, PostgreSQL, and Redis. The application provides user registration and authentication, public and private chat rooms, one-to-one personal messaging, a contacts/friends system, file and image sharing, real-time messaging, user presence tracking, moderation features, persistent message history, and unread indicators. The system targets up to 300 simultaneous users and uses a hybrid REST + WebSocket architecture. Deployment is via Docker Compose.

## Glossary

- **Application**: The YAC Spring Boot web application serving HTML via Thymeleaf and real-time updates via STOMP WebSocket
- **User**: A registered account in the system identified by a unique UUID, unique email, and unique immutable username
- **Room**: A persistent chat channel with a name, description, visibility (PUBLIC, PRIVATE, or DIRECT), an owner, and a member list
- **Public_Room**: A Room with visibility PUBLIC, discoverable in the public catalog and joinable by any authenticated User unless banned
- **Private_Room**: A Room with visibility PRIVATE, joinable only by invitation
- **Direct_Chat**: A Room with visibility DIRECT representing a personal conversation between exactly two Users; functionally equivalent to a Room but with a fixed two-participant list and no admin moderation
- **Owner**: The User who created a Room; the Owner is always an Admin and cannot lose admin privileges or leave the Room
- **Admin**: A RoomMember with the ADMIN role who can moderate messages, members, and bans within a Room
- **Member**: A RoomMember with the MEMBER role who can read and send messages in a Room
- **RoomBan**: A record preventing a specific User from joining or accessing a specific Room
- **Friendship**: A bidirectional relationship between two Users with status PENDING, ACCEPTED, or DECLINED
- **UserBan**: A unidirectional block where the blocker prevents the blocked User from contacting them
- **Message**: A persistent text record sent by a User into a Room, with optional reply reference and edit flag
- **Attachment**: A file or image uploaded alongside a Message, stored on the local filesystem
- **UnreadMarker**: A per-User, per-Room record tracking the last read Message and unread count
- **Presence**: The real-time activity status of a User: ONLINE, AFK, or OFFLINE
- **Session**: A Spring Security session backed by Redis, representing a single browser login
- **Watermark**: A monotonically increasing sequence number per Room used to detect message history gaps and ensure integrity
- **WebSocket_Broker**: The STOMP message broker configured with /topic and /queue prefixes for real-time message delivery
- **REST_API**: The HTTP endpoints used for CRUD operations, history loading, file uploads, and non-real-time interactions
- **Catalog**: The searchable listing of Public_Rooms showing name, description, and member count

## Requirements

### Requirement 1: User Registration

**User Story:** As a visitor, I want to register an account with email, password, and username, so that I can access the chat application.

#### Acceptance Criteria

1. WHEN a visitor submits a registration form with a valid email, unique username, and password, THE Application SHALL create a new User account and redirect to the login page
2. WHEN a visitor submits a registration form with an email that already exists, THE Application SHALL reject the registration and display an error indicating the email is taken
3. WHEN a visitor submits a registration form with a username that already exists, THE Application SHALL reject the registration and display an error indicating the username is taken
4. THE Application SHALL store the password in BCrypt-hashed form
5. THE Application SHALL enforce that the username is immutable after registration

### Requirement 2: Authentication and Session Management

**User Story:** As a registered user, I want to sign in, stay signed in across browser restarts, and manage my active sessions, so that I can securely access the chat from multiple devices.

#### Acceptance Criteria

1. WHEN a User submits valid email and password credentials, THE Application SHALL authenticate the User and redirect to the chat page
2. WHEN a User submits invalid credentials, THE Application SHALL reject the login and display an error message
3. WHEN a User selects the "Keep me signed in" option during login, THE Application SHALL issue a remember-me token valid for 30 days
4. WHEN a User signs out, THE Application SHALL invalidate only the current browser Session and leave other active Sessions unaffected
5. WHEN a User views the active sessions screen, THE Application SHALL display a list of all active Sessions including browser and IP details
6. WHEN a User selects a Session to terminate from the active sessions screen, THE Application SHALL invalidate that specific Session
7. THE Application SHALL persist Session data in Redis to support multi-instance deployment

### Requirement 3: Password Management

**User Story:** As a user, I want to reset my forgotten password and change my current password, so that I can maintain secure access to my account.

#### Acceptance Criteria

1. WHEN a User requests a password reset by providing their registered email, THE Application SHALL send a password reset link
2. WHEN a User submits a new password via a valid reset link, THE Application SHALL update the password hash and invalidate the reset link
3. WHEN a logged-in User submits a password change with the correct current password and a new password, THE Application SHALL update the password hash
4. WHEN a logged-in User submits a password change with an incorrect current password, THE Application SHALL reject the change and display an error

### Requirement 4: Account Deletion

**User Story:** As a user, I want to delete my account, so that my data is removed from the system.

#### Acceptance Criteria

1. WHEN a User confirms account deletion, THE Application SHALL remove the User account
2. WHEN a User account is deleted, THE Application SHALL delete all Rooms owned by that User
3. WHEN a Room is deleted due to owner account deletion, THE Application SHALL permanently delete all Messages and Attachments in that Room
4. WHEN a User account is deleted, THE Application SHALL remove the User membership from all other Rooms
5. WHEN a User account is deleted, THE Application SHALL invalidate all active Sessions for that User

### Requirement 5: User Presence

**User Story:** As a user, I want to see which contacts are online, AFK, or offline, so that I know who is available to chat.

#### Acceptance Criteria

1. WHILE a User has at least one browser tab with active cursor movement within the last 2 seconds, THE Application SHALL report that User Presence as ONLINE
2. WHILE all browser tabs for a User have had no cursor movement for more than 1 minute, THE Application SHALL report that User Presence as AFK
3. WHEN all browser tabs for a User are closed or hibernated by the browser, THE Application SHALL report that User Presence as OFFLINE
4. WHEN a User Presence status changes, THE WebSocket_Broker SHALL broadcast the update to all connected Users who have that User in their contact list or share a Room within 2 seconds
5. THE Application SHALL track cursor movement events on each browser tab and send periodic activity heartbeats to the server via WebSocket
6. WHEN a browser tab is hibernated by the browser, THE Application SHALL treat the absence of heartbeats as tab inactivity rather than requiring an explicit inactive signal

### Requirement 6: Contacts and Friend Requests

**User Story:** As a user, I want to manage a friend list and send friend requests, so that I can communicate with people I know.

#### Acceptance Criteria

1. WHEN a User sends a friend request by username or from a Room member list, THE Application SHALL create a Friendship record with status PENDING
2. WHEN a User sends a friend request, THE Application SHALL allow the User to include optional request text
3. WHEN the recipient accepts a friend request, THE Application SHALL update the Friendship status to ACCEPTED
4. WHEN the recipient declines a friend request, THE Application SHALL update the Friendship status to DECLINED
5. WHEN a User removes a friend, THE Application SHALL delete the Friendship record
6. THE Application SHALL display the friend list with each friend's current Presence status

### Requirement 7: User-to-User Ban

**User Story:** As a user, I want to ban another user, so that the banned user cannot contact me.

#### Acceptance Criteria

1. WHEN a User bans another User, THE Application SHALL create a UserBan record
2. WHEN a UserBan exists, THE Application SHALL prevent the blocked User from sending any messages or friend requests to the blocker
3. WHEN a UserBan is created between two Users who have an existing Friendship, THE Application SHALL terminate that Friendship
4. WHEN a UserBan exists between two Users who have an existing Direct_Chat, THE Application SHALL make that Direct_Chat read-only for both participants while preserving the message history
5. WHEN a User removes a UserBan, THE Application SHALL delete the UserBan record and restore the ability to send friend requests

### Requirement 8: Chat Room Creation and Properties

**User Story:** As a user, I want to create chat rooms with configurable properties, so that I can organize conversations.

#### Acceptance Criteria

1. WHEN a User creates a Room with a unique name, description, and visibility setting, THE Application SHALL create the Room and assign the creating User as Owner
2. WHEN a User creates a Room, THE Application SHALL add the Owner as a RoomMember with the OWNER role
3. WHEN a User attempts to create a Room with a name that already exists, THE Application SHALL reject the creation and display an error
4. THE Application SHALL enforce that each Room has exactly one Owner
5. THE Application SHALL support Room visibility values of PUBLIC, PRIVATE, and DIRECT

### Requirement 9: Public Room Catalog and Joining

**User Story:** As a user, I want to browse and search public rooms and join them freely, so that I can discover conversations.

#### Acceptance Criteria

1. THE Catalog SHALL display all Public_Rooms with their name, description, and current member count
2. WHEN a User enters a search term in the Catalog, THE Application SHALL filter Public_Rooms by name or description matching the search term
3. WHEN an authenticated User who is not banned from a Public_Room requests to join, THE Application SHALL add the User as a Member
4. WHEN a User who has a RoomBan for a Public_Room attempts to join, THE Application SHALL reject the join and display an error

### Requirement 10: Private Room Access

**User Story:** As a user, I want to join private rooms only by invitation, so that private conversations remain restricted.

#### Acceptance Criteria

1. THE Application SHALL exclude Private_Rooms from the public Catalog
2. WHEN a RoomMember invites another User to a Private_Room, THE Application SHALL create a RoomInvitation record
3. WHEN an invited User accepts a RoomInvitation, THE Application SHALL add the User as a Member and delete the RoomInvitation
4. WHEN a non-invited User attempts to join a Private_Room, THE Application SHALL reject the join request

### Requirement 11: Room Membership Management

**User Story:** As a user, I want to join and leave rooms freely, so that I can control my participation.

#### Acceptance Criteria

1. WHEN a Member leaves a Room, THE Application SHALL remove the RoomMember record
2. WHEN the Owner attempts to leave a Room, THE Application SHALL reject the request and inform the Owner that the Room must be deleted instead
3. WHEN a Room is deleted, THE Application SHALL permanently delete all Messages and Attachments in that Room
4. WHEN a Room is deleted, THE Application SHALL remove all RoomMember, RoomBan, and RoomInvitation records for that Room
5. IF a User loses access to a Room, THEN THE Application SHALL prevent that User from viewing Messages or downloading Attachments from that Room

### Requirement 12: Room Moderation — Admin Actions

**User Story:** As a room admin, I want to manage members, bans, and messages, so that I can maintain order in the room.

#### Acceptance Criteria

1. WHEN an Admin removes a Member from a Room, THE Application SHALL create a RoomBan record and remove the RoomMember record
2. WHEN an Admin bans a User from a Room, THE Application SHALL create a RoomBan record preventing that User from rejoining
3. WHEN an Admin removes a User from the Room ban list, THE Application SHALL delete the RoomBan record
4. WHEN an Admin deletes a Message in a Room, THE Application SHALL permanently remove that Message
5. WHEN an Admin views the banned users list, THE Application SHALL display each banned User with the name of the Admin who issued the ban and the ban timestamp
6. WHEN an Admin attempts to remove admin status from the Owner, THE Application SHALL reject the action
7. THE Owner SHALL be able to grant and revoke Admin role for any Member in the Room
8. WHEN an Admin removes admin status from another Admin who is not the Owner, THE Application SHALL update that RoomMember role to MEMBER

### Requirement 13: Messaging

**User Story:** As a user, I want to send, edit, and delete messages with text, emoji, and reply references, so that I can communicate in rooms and personal chats.

#### Acceptance Criteria

1. WHEN a User sends a Message with text content to a Room, THE Application SHALL persist the Message and broadcast it to all Room Members via the WebSocket_Broker
2. THE Application SHALL enforce a maximum Message text size of 3072 bytes (3 KB) encoded in UTF-8
3. WHEN a User replies to an existing Message, THE Application SHALL store the reply reference and display the referenced Message as a visual quote in the UI
4. WHEN a User edits their own Message, THE Application SHALL update the Message content and set the edited flag to true
5. WHEN a Message has been edited, THE Application SHALL display a gray "edited" indicator in the UI
6. WHEN a User deletes their own Message, THE Application SHALL permanently remove the Message
7. WHEN an Admin deletes a Message in a Room chat, THE Application SHALL permanently remove that Message
8. THE Application SHALL support plain text, multiline text, and emoji characters in Message content

### Requirement 14: Personal Messaging

**User Story:** As a user, I want to exchange personal messages with friends, so that I can have private one-to-one conversations.

#### Acceptance Criteria

1. WHEN two Users who are friends and have no mutual UserBan initiate a personal conversation, THE Application SHALL create a Direct_Chat Room with both Users as Members
2. WHEN a User attempts to send a personal message to a non-friend, THE Application SHALL reject the message
3. WHEN a User attempts to send a personal message to a User who has banned them, THE Application SHALL reject the message
4. THE Application SHALL support the same Message and Attachment features in Direct_Chats as in regular Rooms
5. THE Application SHALL enforce that Direct_Chats have no Admin moderation capabilities

### Requirement 15: File and Image Attachments

**User Story:** As a user, I want to share files and images in chats, so that I can exchange documents and media.

#### Acceptance Criteria

1. WHEN a User uploads a file Attachment with a size up to 20 MB, THE Application SHALL store the file on the local filesystem and create an Attachment record linked to the Message
2. WHEN a User uploads an image Attachment with a size up to 3 MB, THE Application SHALL store the image on the local filesystem and create an Attachment record linked to the Message
3. WHEN a User uploads a file exceeding 20 MB, THE Application SHALL reject the upload and display a file size error
4. WHEN a User uploads an image exceeding 3 MB, THE Application SHALL reject the upload and display an image size error
5. THE Application SHALL preserve the original file name in the Attachment record
6. WHEN a User adds an Attachment, THE Application SHALL allow the User to include an optional comment
7. WHEN a User pastes an image from the clipboard, THE Application SHALL treat the paste as an image upload
8. WHEN a current Room Member requests to download an Attachment, THE Application SHALL serve the file
9. WHEN a User who is not a current Room Member requests to download an Attachment from that Room, THE Application SHALL deny the download

### Requirement 16: Message History and Infinite Scroll

**User Story:** As a user, I want to scroll through message history including very old messages, so that I can review past conversations.

#### Acceptance Criteria

1. THE Application SHALL store Messages persistently and display them in chronological order
2. WHEN a User scrolls up in a chat, THE Application SHALL load older Messages in pages via the REST_API using cursor-based pagination
3. THE Application SHALL support progressive loading of message history for Rooms containing 100,000 or more Messages
4. THE Application SHALL assign a monotonically increasing Watermark sequence number to each Message within a Room
5. WHEN a client detects a gap in Watermark sequence numbers, THE Application SHALL allow the client to re-query the missing Message range
6. WHEN the User is scrolled to the bottom of the chat, THE Application SHALL auto-scroll to display new incoming Messages
7. WHILE the User has scrolled up to read older Messages, THE Application SHALL not force auto-scroll to the bottom

### Requirement 17: Unread Message Indicators

**User Story:** As a user, I want to see which rooms and contacts have unread messages, so that I can prioritize my attention.

#### Acceptance Criteria

1. WHEN a new Message arrives in a Room that a User is not currently viewing, THE Application SHALL increment the unread count in the UnreadMarker for that User and Room
2. WHEN a User opens a Room, THE Application SHALL reset the UnreadMarker unread count to zero and update the last read Message reference
3. THE Application SHALL display the unread count as a badge next to the Room name and contact name in the sidebar
4. THE Application SHALL not accumulate unbounded unread counts for Users who have been offline for extended periods; the UnreadMarker SHALL store only the last-read position and compute unread count on demand up to a display cap

### Requirement 18: Real-Time Message Delivery

**User Story:** As a user, I want to receive messages in real time without refreshing the page, so that conversations feel live.

#### Acceptance Criteria

1. WHEN a Message is sent to a Room, THE WebSocket_Broker SHALL deliver the Message to all connected Room Members within 3 seconds
2. THE Application SHALL use STOMP over WebSocket for real-time Message delivery and Presence updates
3. THE Application SHALL use the REST_API for message history loading, file uploads, room management, and other non-real-time operations
4. WHEN a User is offline at the time a Message is sent, THE Application SHALL persist the Message and deliver it when the User next connects via the REST_API history load
5. THE Application SHALL not maintain unbounded message queues for offline Users; persisted Messages SHALL be retrieved via cursor-based history queries on reconnection

### Requirement 19: Chat UI Layout

**User Story:** As a user, I want a classic web chat layout with rooms, contacts, message area, and input controls, so that navigation is intuitive.

#### Acceptance Criteria

1. THE Application SHALL render a layout with a top menu bar, a central message area, a message input area at the bottom, and a sidebar for Rooms and contacts
2. THE Application SHALL display Rooms and contacts in the right sidebar
3. WHEN a User enters a Room, THE Application SHALL compact the Room list into an accordion-style view and display Room Members with their Presence status on the right side
4. THE Application SHALL provide a message input area supporting multiline text entry, emoji input, file and image attachment buttons, and a reply-to-message control
5. THE Application SHALL render admin actions (ban, unban, remove member, manage admins, view banned users, delete messages, delete room) through modal dialogs accessible from room management menus

### Requirement 20: Scalability and Performance

**User Story:** As a system operator, I want the application to handle the expected load reliably, so that users have a smooth experience.

#### Acceptance Criteria

1. THE Application SHALL support up to 300 simultaneously connected Users
2. THE Application SHALL support Rooms with up to 1000 Members
3. WHEN a Message is sent, THE Application SHALL deliver it to recipients within 3 seconds
4. WHEN a User Presence status changes, THE Application SHALL propagate the update within 2 seconds
5. THE Application SHALL remain responsive when a Room contains 100,000 or more Messages in its history
6. THE Application SHALL be deployable via a single `docker compose up` command

### Requirement 21: Jabber/XMPP Protocol Support (Advanced/Optional)

**User Story:** As a system operator, I want the server to support Jabber/XMPP protocol connections and server federation, so that users can connect with standard XMPP clients and communicate across federated servers.

#### Acceptance Criteria

1. WHERE Jabber support is enabled, THE Application SHALL accept connections from standard XMPP/Jabber clients
2. WHERE Jabber support is enabled, THE Application SHALL support server-to-server federation allowing message exchange between two YAC server instances
3. WHERE Jabber support is enabled, THE Application SHALL use an existing Java XMPP library available in the Maven ecosystem
4. WHERE Jabber support is enabled, THE Application SHALL provide an admin connection dashboard UI showing active XMPP connections
5. WHERE Jabber support is enabled, THE Application SHALL provide a federation traffic statistics UI showing message counts between federated servers
6. WHERE Jabber support is enabled, THE Application SHALL support a load test scenario with 50 or more clients connected to each of two federated servers exchanging messages bidirectionally
