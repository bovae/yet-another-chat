# Bugfix Requirements Document

## Introduction

This document covers 18 UX and logic bugs in the YAC (Yet Another Chat) application spanning room management issues, real-time WebSocket rendering inconsistencies, ban enforcement gaps, invitation handling errors, presence/AFK tracking failures, and UI display problems. These bugs degrade user experience, expose security vulnerabilities in ban enforcement, and cause data integrity issues with room invitations.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a user is in a direct message (DM) conversation THEN the system allows the user to leave the direct chat room, which should not be permitted for DM conversations

1.2 WHEN a user replies to a message in real-time (via WebSocket) THEN the reply-to preview shows "Original message deleted" as the original message text even though the original message was NOT deleted; after page refresh the correct original message text is displayed

1.3 WHEN a message that has replies is deleted in real-time (via WebSocket) THEN the reply quotes on other messages show "Original message deleted" text; after page refresh the "Original message deleted" text disappears entirely (inconsistent rendering between live and server-rendered state)

1.4 WHEN a user views the direct messages section in the sidebar THEN the system displays both a "Saved Messages" button AND a separate "Saved Messages" contact entry below it, creating a duplicate

1.5 WHEN a user views the Contacts section at /chat THEN the system intermittently shows the user themselves instead of their friend; navigating away and back corrects the display to show the friend

1.6 WHEN a user has a display name set THEN the system shows the username in chat messages instead of the display name

1.7 WHEN a POST request is made to /api/password/reset THEN the system returns HTTP 302 Found instead of HTTP 200 OK

1.8 WHEN a user views certain UI elements THEN the system requires hovering to see the full line content, which should be visible without hover interaction

1.9 WHEN a user is banned from a channel THEN the system continues to display the banned user in the channel member list

1.10 WHEN a user is banned from a channel THEN the system still allows the banned user to view/access the channel content

1.11 WHEN a user is banned from a channel THEN the system still allows the banned user to send messages to that channel

1.12 WHEN the ban list endpoint (`RoomBanApiController.listBans()`) is called THEN the system throws `org.hibernate.LazyInitializationException: Could not initialize proxy [com.bovae.yac.model.entity.User#...]` because `toBanResponse()` accesses `user.getUsername()` on a lazy-loaded User proxy outside a Hibernate session

1.13 WHEN a non-owner user accesses the ban list endpoint for a room THEN the system may return the ban list without restricting access to room owners only

1.14 WHEN a user declines a room invitation and someone sends a new invitation to the same user for the same room THEN the system throws `DataIntegrityViolationException` with `duplicate key value violates unique constraint "room_invitations_room_id_invitee_id_key"` because the old declined invitation record is not cleaned up before inserting a new one

1.15 WHEN a user has previously declined an invitation to a room THEN the system does not allow a new invitation to be sent to that user for the same room due to the unique constraint violation described in 1.14

1.16 WHEN a user switches to a different browser tab (tab loses focus) THEN the system continues sending heartbeats and the user never transitions to AFK status

1.17 WHEN a user closes the browser tab entirely THEN the system stops receiving heartbeats but the user does not transition to AFK or OFFLINE status (Redis TTL not expiring or not being checked properly)

1.18 WHEN a user views the left menu sidebar THEN the system only displays the correct online/offline status of other users after navigating into a chat with them; the sidebar should show correct presence status without requiring navigation to the chat

### Expected Behavior (Correct)

2.1 WHEN a user is in a direct message (DM) conversation THEN the system SHALL NOT allow the user to leave the room; the leave option SHALL be hidden or disabled for DIRECT visibility rooms

2.2 WHEN a user replies to a message in real-time (via WebSocket) THEN the system SHALL display the correct original message text in the reply-to preview immediately, matching what would be shown after a page refresh

2.3 WHEN a message that has replies is deleted in real-time (via WebSocket) THEN the system SHALL update the reply quotes to show "Original message deleted" text consistently, matching the server-rendered state after page refresh (i.e., the reply quote should show "Original message deleted" both live and after refresh, OR neither — whichever is the intended design)

2.4 WHEN a user views the direct messages section in the sidebar THEN the system SHALL display only one "Saved Messages" entry, eliminating the duplicate

2.5 WHEN a user views the Contacts section THEN the system SHALL consistently display the correct friend (not self) regardless of navigation timing or page state

2.6 WHEN a user has a display name set THEN the system SHALL use the display name in chat messages instead of the username

2.7 WHEN a POST request is made to /api/password/reset THEN the system SHALL return HTTP 200 OK with an appropriate JSON response body

2.8 WHEN a user views UI elements THEN the system SHALL display the full line content without requiring hover interaction

2.9 WHEN a user is banned from a channel THEN the system SHALL immediately remove the banned user from the channel member list

2.10 WHEN a user is banned from a channel THEN the system SHALL prevent the banned user from viewing or accessing the channel content, returning an appropriate error or redirect

2.11 WHEN a user is banned from a channel THEN the system SHALL prevent the banned user from sending messages to that channel, returning an appropriate error

2.12 WHEN the ban list endpoint is called THEN the system SHALL return the list of banned users with their usernames without throwing `LazyInitializationException`, by eagerly fetching the User entity in the repository query

2.13 WHEN a non-owner user attempts to access the ban list endpoint for a room THEN the system SHALL deny access and return HTTP 403 Forbidden; only the room owner SHALL be able to view the ban list

2.14 WHEN a user declines a room invitation and someone sends a new invitation to the same user for the same room THEN the system SHALL handle the re-invitation gracefully by either deleting/updating the old declined record or using an upsert strategy, avoiding the unique constraint violation

2.15 WHEN a user has previously declined an invitation to a room THEN the system SHALL allow a new invitation to be sent to that user, while maintaining the constraint that only one active (pending) invitation per user per room exists at a time

2.16 WHEN a user switches to a different browser tab (tab loses focus) THEN the system SHALL stop sending heartbeats (or send reduced-frequency heartbeats) so that the user transitions to AFK status after the heartbeat TTL expires

2.17 WHEN a user closes the browser tab THEN the system SHALL ensure the user transitions to OFFLINE status after the Redis heartbeat TTL expires (typically 30 seconds)

2.18 WHEN a user views the left menu sidebar THEN the system SHALL display the correct online/offline/AFK status of other users without requiring navigation into a chat with them; presence status SHALL be fetched and displayed proactively in the sidebar

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a user leaves a non-DM room (PUBLIC or PRIVATE) THEN the system SHALL CONTINUE TO allow the user to leave and remove them from the room membership

3.2 WHEN a message is sent via WebSocket and no deletion occurs THEN the system SHALL CONTINUE TO display messages correctly in real-time with proper reply-to previews after page refresh

3.3 WHEN a message without replies is deleted THEN the system SHALL CONTINUE TO remove only that message from the DOM without affecting other messages

3.4 WHEN a user views the direct messages section THEN the system SHALL CONTINUE TO display the Saved Messages functionality (just not duplicated)

3.5 WHEN a user views the Contacts section with multiple friends THEN the system SHALL CONTINUE TO display all friends correctly in the contacts list

3.6 WHEN a user does not have a display name set THEN the system SHALL CONTINUE TO display the username in chat messages

3.7 WHEN a POST request is made to /api/password/change THEN the system SHALL CONTINUE TO process password changes correctly

3.8 WHEN a user who is NOT banned accesses a channel THEN the system SHALL CONTINUE TO allow them to view content and send messages normally

3.9 WHEN a room owner views the ban list THEN the system SHALL CONTINUE TO display all banned users with their details

3.10 WHEN a user accepts a room invitation THEN the system SHALL CONTINUE TO add them as a member and redirect to the room

3.11 WHEN a user sends a first-time invitation to another user for a room THEN the system SHALL CONTINUE TO create the invitation record successfully

3.12 WHEN a user is actively using the application (tab focused) THEN the system SHALL CONTINUE TO send heartbeats and maintain ONLINE presence status

3.13 WHEN a user views a chat room THEN the system SHALL CONTINUE TO display the correct presence status of the other user in that chat

3.14 WHEN a user is a member of a room and navigates to it THEN the system SHALL CONTINUE TO render the room page with full message history and input controls
