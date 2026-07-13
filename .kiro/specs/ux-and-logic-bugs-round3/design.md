# UX and Logic Bugs Round 3 — Bugfix Design

## Overview

This design addresses 18 bugs spanning DM room management, WebSocket reply-to rendering inconsistencies, sidebar display issues, ban enforcement gaps, Hibernate lazy-loading exceptions, invitation handling errors, presence/AFK tracking failures, and UI display problems. The fixes target backend service logic, repository queries, security configuration, frontend JavaScript, and CSS.

## Glossary

- **Bug_Condition (C)**: The specific input or state that triggers each bug
- **Property (P)**: The desired correct behavior when the bug condition is met
- **Preservation**: Existing behaviors that must remain unchanged after fixes
- **DIRECT room**: A room with `visibility = DIRECT`, used for DMs and Saved Messages
- **LazyInitializationException**: Hibernate exception when accessing a lazy-loaded proxy outside a session
- **STOMP**: Simple Text Oriented Messaging Protocol used for WebSocket communication
- **Heartbeat**: Periodic signal sent from client to server to indicate user presence
- **AFK**: Away From Keyboard — user has not interacted with the tab for 60+ seconds
- **TTL**: Time To Live — Redis key expiration duration (90s for presence)

## Bug Details

### Bug Condition

**Bug 1: DM leave prevention**

```
FUNCTION isBugCondition_1(input)
  INPUT: input of type {POST /api/rooms/{roomId}/leave}
  OUTPUT: boolean

  RETURN input.room.visibility == DIRECT
         AND RoomMemberService.leaveRoom() does NOT check room visibility
         AND user is allowed to leave a DIRECT room
END FUNCTION
```

**Bug 2: WebSocket reply-to showing "Original message deleted" incorrectly**

```
FUNCTION isBugCondition_2(input)
  INPUT: input of type {WebSocket new message event with reply_to_id}
  OUTPUT: boolean

  RETURN input.message.reply_to_id != null
         AND ChatMessageHandler.sendMessage() does NOT include replyToSenderUsername
              or replyToContentSnippet in the WebSocket broadcast
         AND app.js onNewMessage() receives null for reply_to_sender_username
         AND reply quote renders "Original message deleted" because replySender is null
END FUNCTION
```

**Bug 3: WebSocket message deletion reply quote inconsistency**

```
FUNCTION isBugCondition_3(input)
  INPUT: input of type {WebSocket delete event for a message that has replies}
  OUTPUT: boolean

  RETURN input.messageDeleted == true
         AND existsReplyQuoteInDOM(input.deletedMessageId)
         AND handleDeleteMessage() updates reply quotes to "Original message deleted"
         AND server-rendered state (after refresh) shows NO reply quote at all
              (because replyToSenderUsername is null when replyTo message is deleted)
         AND live state != server-rendered state (inconsistency)
END FUNCTION
```

**Bug 4: Duplicate "Saved Messages" in sidebar**

```
FUNCTION isBugCondition_4(input)
  INPUT: input of type {sidebar render for user with Saved Messages room}
  OUTPUT: boolean

  RETURN sidebar template has static "Saved Messages" button (id="saved-messages-btn")
         AND populateRooms() also renders the Saved Messages room in direct-chat-list
              as "Saved Messages 🔖" (because otherUsername/otherDisplayName are null for self-DM)
         AND both are visible simultaneously
END FUNCTION
```

**Bug 5: Contacts showing self instead of friend**

```
FUNCTION isBugCondition_5(input)
  INPUT: input of type {friendship list rendered in sidebar}
  OUTPUT: boolean

  RETURN getCurrentUserId() returns a value
         AND friendship API returns friendship where requester_id != currentUserId
              AND recipient_id != currentUserId (due to type/format mismatch)
         AND the wrong user (self) is displayed as the friend
END FUNCTION
```

**Bug 6: Display name not used in chats**

```
FUNCTION isBugCondition_6(input)
  INPUT: input of type {message rendered in chat room}
  OUTPUT: boolean

  RETURN input.sender.displayName != null
         AND ChatMessageResponse.senderUsername contains username (not displayName)
         AND room.html template renders msg.senderUsername() in message header
         AND no senderDisplayName field exists in ChatMessageResponse
END FUNCTION
```

**Bug 7: Password reset returning 302 instead of 200**

```
FUNCTION isBugCondition_7(input)
  INPUT: input of type {POST /api/password/reset from unauthenticated user}
  OUTPUT: boolean

  RETURN input.user is NOT authenticated
         AND SecurityConfig.permitAll() does NOT include "/api/password/reset"
         AND Spring Security intercepts the request
         AND returns 302 redirect to /login
END FUNCTION
```

**Bug 8: UI hover issue for full line visibility**

```
FUNCTION isBugCondition_8(input)
  INPUT: input of type {CSS render of message-item}
  OUTPUT: boolean

  RETURN .message-item:hover CSS rule applies background + padding + negative margin
         AND this causes layout shift on hover
         AND content that was visible becomes hidden or shifted
         AND user must hover to see full line content correctly
END FUNCTION
```

**Bug 9: Ban enforcement — member list still shows banned user**

```
FUNCTION isBugCondition_9(input)
  INPUT: input of type {ban user from room via ModerationService.banUserFromRoom()}
  OUTPUT: boolean

  RETURN banUserFromRoom() creates RoomBan record
         AND banUserFromRoom() does NOT remove the user from RoomMember table
         AND roomMemberRepository.findByRoomWithUsers(room) still returns the banned user
         AND member list still displays the banned user
END FUNCTION
```

**Bug 10: Ban enforcement — banned user can still view channel**

```
FUNCTION isBugCondition_10(input)
  INPUT: input of type {GET /chat/rooms/{id} by banned user}
  OUTPUT: boolean

  RETURN roomBanRepository.existsByRoomAndUser(room, user) == true
         AND user is still a member (due to Bug 9)
         AND ChatWebController.roomView() only checks isMember, not isBanned
         AND banned user can view room content
END FUNCTION
```

**Bug 11: Ban enforcement — banned user can still send messages**

```
FUNCTION isBugCondition_11(input)
  INPUT: input of type {WebSocket /app/chat.send from banned user}
  OUTPUT: boolean

  RETURN roomBanRepository.existsByRoomAndUser(room, sender) == true
         AND MessageService.sendMessage() only checks isMember, not isBanned
         AND ChatMessageHandler does not check ban status before sending
         AND banned user can send messages
END FUNCTION
```

**Bug 12: LazyInitializationException on ban list**

```
FUNCTION isBugCondition_12(input)
  INPUT: input of type {GET /api/rooms/{roomId}/bans}
  OUTPUT: boolean

  RETURN RoomBanApiController.listBans() calls roomBanRepository.findByRoom(room)
         AND RoomBan.user and RoomBan.bannedBy are FetchType.LAZY
         AND toBanResponse() accesses ban.getUser().getUsername() and ban.getBannedBy().getUsername()
         AND open-in-view: false
         AND LazyInitializationException is thrown
END FUNCTION
```

**Bug 13: Ban list access restricted to owner only**

```
FUNCTION isBugCondition_13(input)
  INPUT: input of type {GET /api/rooms/{roomId}/bans by non-owner user}
  OUTPUT: boolean

  RETURN input.user is NOT the room owner
         AND RoomBanApiController.listBans() only calls resolveUser(principal)
         AND does NOT check if user is owner/admin of the room
         AND returns ban list to unauthorized user
END FUNCTION
```

**Bug 14: Invitation re-send after decline — unique constraint violation**

```
FUNCTION isBugCondition_14(input)
  INPUT: input of type {POST /api/rooms/{roomId}/invitations for previously-declined invitee}
  OUTPUT: boolean

  RETURN previous invitation for (room, invitee) was declined via DELETE endpoint
         AND declineOrCancelInvitation() deletes the record
         AND new invitation is created with same (room_id, invitee_id)
         AND unique constraint "room_invitations_room_id_invitee_id_key" is violated
         AND DataIntegrityViolationException is thrown
END FUNCTION
```

**Bug 15: Invitation re-send after decline — cannot re-invite**

```
FUNCTION isBugCondition_15(input)
  INPUT: input of type {POST /api/rooms/{roomId}/invitations for user who previously declined}
  OUTPUT: boolean

  RETURN same root cause as Bug 14
         AND the system does not allow re-invitation due to constraint violation
END FUNCTION
```

**Bug 16: Presence/AFK tracking — tab loses focus but heartbeats continue**

```
FUNCTION isBugCondition_16(input)
  INPUT: input of type {user switches to different browser tab}
  OUTPUT: boolean

  RETURN document.hidden == true (tab lost focus)
         AND presence.js heartbeatTimer continues running via setInterval
         AND sendHeartbeat() continues sending heartbeats
         AND active flag is computed from isAllTabsIdle60s() which checks cursor activity
         AND heartbeats keep the Redis TTL alive preventing AFK/OFFLINE transition
END FUNCTION
```

**Bug 17: Presence/AFK tracking — tab close doesn't transition to OFFLINE**

```
FUNCTION isBugCondition_17(input)
  INPUT: input of type {user closes browser tab}
  OUTPUT: boolean

  RETURN beforeunload fires and stopHeartbeat() is called
         AND broadcastMessage({ type: 'closing' }) notifies other tabs
         AND if this is the LAST tab, no more heartbeats are sent
         AND Redis key has TTL of 90 seconds
         AND cleanupStalePresence() runs every 30 seconds
         AND user should transition to OFFLINE after TTL expires
         AND the issue is that the TTL is too long (90s) relative to heartbeat interval (10s)
              OR cleanupStalePresence() is not detecting expired keys correctly
END FUNCTION
```

**Bug 18: Sidebar presence status display**

```
FUNCTION isBugCondition_18(input)
  INPUT: input of type {sidebar render showing contacts}
  OUTPUT: boolean

  RETURN populateContacts() renders presence dots for contacts
         AND fetchInitialPresence() is called with userIds
         AND subscribeToAllVisibleUsers() subscribes to /topic/presence.{userId}
         AND presence dots only update AFTER navigating into a chat with the user
         AND the issue is timing: subscriptions happen before STOMP is connected
              OR fetchInitialPresence() fails silently
END FUNCTION
```

### Examples

- Bug 1: User in a DM clicks "Leave" → successfully leaves the DM room, breaking the conversation
- Bug 2: User A replies to User B's message via WebSocket → reply preview shows "Original message deleted" instead of the actual message text; page refresh shows correct text
- Bug 3: User A deletes a message that User B replied to → User B sees "Original message deleted" in reply quote live; after refresh, the reply quote disappears entirely
- Bug 4: User opens DM section → sees "Saved Messages 🔖" button AND a "Saved Messages 🔖" entry in the direct chat list below it
- Bug 5: User views Contacts → intermittently sees themselves instead of their friend
- Bug 6: User "alice" with displayName "Alice Smith" sends a message → header shows "alice" instead of "Alice Smith"
- Bug 7: Unauthenticated user submits password reset form → fetch returns 302 redirect to /login instead of 200
- Bug 8: Long message content is partially hidden until user hovers over the message row
- Bug 9: Admin bans user from channel → user still appears in member list
- Bug 10: Banned user navigates to `/chat/rooms/{id}` → can still see all messages
- Bug 11: Banned user sends message via WebSocket → message is broadcast to room
- Bug 12: Owner opens ban list modal → 500 error with LazyInitializationException
- Bug 13: Regular member calls GET `/api/rooms/{roomId}/bans` → receives the full ban list
- Bug 14: User declines invitation, inviter re-sends → 500 error with DataIntegrityViolationException
- Bug 15: Same as Bug 14 — re-invitation is impossible after decline
- Bug 16: User switches to another tab → stays ONLINE indefinitely because heartbeats continue
- Bug 17: User closes last tab → stays ONLINE for 90+ seconds instead of transitioning to OFFLINE within ~30s
- Bug 18: User opens sidebar → all contacts show grey (offline) dots; only after clicking into a chat does the dot update

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Users can leave PUBLIC and PRIVATE rooms normally (3.1)
- Messages sent via WebSocket without reply-to continue to display correctly (3.2)
- Messages without replies that are deleted continue to be removed from DOM without affecting other messages (3.3)
- Saved Messages functionality continues to work (just not duplicated) (3.4)
- All friends continue to display correctly in the contacts list (3.5)
- Users without a display name continue to show username in chat messages (3.6)
- Password change endpoint continues to work correctly for authenticated users (3.7)
- Non-banned users continue to access channels, view content, and send messages normally (3.8)
- Room owners continue to view the ban list with all banned user details (3.9)
- First-time invitations continue to create records successfully (3.10, 3.11)
- Users actively using the application (tab focused) continue to maintain ONLINE status (3.12)
- Presence status in chat room member lists continues to display correctly (3.13)
- Room page continues to render with full message history and input controls for members (3.14)

**Scope:**
All inputs that do NOT trigger the specific bug conditions should be completely unaffected. This includes normal message sending, room creation, friend management, and all other existing functionality.

## Hypothesized Root Cause

### Bug 1: DM leave prevention

`RoomMemberService.leaveRoom()` checks if the user is the OWNER (preventing owner from leaving) but does NOT check if the room is a DIRECT room. DIRECT rooms should never allow leaving since they represent permanent 1:1 conversations.

### Bug 2: WebSocket reply-to showing "Original message deleted"

In `ChatMessageHandler.sendMessage()`, the `ChatMessageResponse` is constructed with `replyToId` set to `request.replyToId()` but `replyToSenderUsername` and `replyToContentSnippet` are both set to `null`. The `app.js` `onNewMessage()` handler checks `msg.reply_to_sender_username` — when it's null, the template renders "Original message deleted" (matching the server-rendered logic in `room.html` where `th:unless="${msg.replyToSenderUsername() != null}"` shows "Original message deleted").

### Bug 3: WebSocket message deletion reply quote inconsistency

When a message is deleted via `handleDeleteMessage()` in `app.js`, it updates reply quotes to show "Original message deleted". However, after page refresh, the server-rendered state shows NO reply quote at all because `MessageService.toResponse()` sets `replyToSenderUsername = null` when `replyTo` is null (the deleted message), and the template's `th:if="${msg.replyToId() != null}"` still renders the reply-quote div but with "Original message deleted" text. The inconsistency is that the live delete shows "Original message deleted" but the server-rendered version also shows it — so the actual bug may be that after refresh the reply-quote div disappears entirely because `replyToId` in the DB still references the deleted message but the JOIN returns null.

### Bug 4: Duplicate "Saved Messages" in sidebar

The sidebar template (`fragments/sidebar.html`) has a static `<button id="saved-messages-btn">Saved Messages 🔖</button>`. Additionally, `populateRooms()` fetches `/api/rooms/my` which includes the Saved Messages room (visibility=DIRECT, single member). The `createRoomItem()` function renders it as "Saved Messages 🔖" in the `direct-chat-list`. Both are visible simultaneously.

### Bug 5: Contacts showing self instead of friend

In `populateContacts()`, the logic determines the friend by checking `friendship.requester_id === currentUserId`. If `getCurrentUserId()` returns a different format (e.g., with/without dashes, different case) than what the API returns, the comparison fails and the wrong user is selected as the "friend". The existing guard `if (String(friendId) === String(currentUserId)) { return; }` should catch this, but if the initial selection logic picks the wrong side, `friendId` would be the other user's ID (correct) but `friendUsername` would be wrong.

### Bug 6: Display name not used in chats

`ChatMessageResponse` only has `senderUsername` field — there is no `senderDisplayName` field. The `MessageService.toResponse()` method calls `message.getSender().getUsername()` but never accesses `message.getSender().getDisplayName()`. The template `room.html` renders `msg.senderUsername()` in the message header. The WebSocket broadcast in `ChatMessageHandler` also only sends `sender.getUsername()`.

### Bug 7: Password reset returning 302

`SecurityConfig` permits `/reset-password` (the page) but does NOT permit `/api/password/reset` (the API endpoint). When an unauthenticated user submits the reset form, the fetch to `/api/password/reset` is intercepted by Spring Security and redirected to `/login` (302). The endpoint needs to be added to the `permitAll()` list.

### Bug 8: UI hover issue for full line visibility

The `.message-item:hover` CSS rule applies `padding: 0.25rem` and `margin: -0.25rem` which causes a layout shift. This can cause content to be clipped or shifted, requiring hover to see the full line. The negative margin combined with `border-radius` creates visual artifacts.

### Bug 9: Ban enforcement — member list

`ModerationService.banUserFromRoom()` creates a `RoomBan` record but does NOT remove the user from the `RoomMember` table. Unlike `kickMember()` which calls both `roomBanRepository.save(ban)` AND `roomMemberRepository.delete(target)`, `banUserFromRoom()` only saves the ban. The banned user remains a member and appears in the member list.

### Bug 10: Ban enforcement — view access

`ChatWebController.roomView()` checks `isMember` but does not check if the user is banned. Since Bug 9 means banned users remain members, they pass the `isMember` check and can view room content.

### Bug 11: Ban enforcement — send messages

`MessageService.sendMessage()` checks `roomMemberService.isMember(room, sender)` but does not check ban status. `ChatMessageHandler.sendMessage()` also has no ban check. A banned user who is still a member (Bug 9) can send messages.

### Bug 12: LazyInitializationException on ban list

`RoomBanApiController.listBans()` calls `roomBanRepository.findByRoom(room)` which returns `List<RoomBan>` with lazy-loaded `user` and `bannedBy` associations. The `toBanResponse()` method accesses `ban.getUser().getUsername()` and `ban.getBannedBy().getUsername()`. Since `open-in-view: false` and there's no `@Transactional`, these lazy proxies throw `LazyInitializationException`.

### Bug 13: Ban list access not restricted

`RoomBanApiController.listBans()` calls `resolveUser(principal)` but does not verify the user is the room owner or admin. Any authenticated user can view the ban list for any room.

### Bug 14-15: Invitation re-send after decline

`RoomInvitationApiController.declineOrCancelInvitation()` calls `roomInvitationRepository.delete(invitation)` which removes the record. However, the `inviteUser()` method does not check for existing invitations before creating a new one. The unique constraint `(room_id, invitee_id)` should not be violated if the old record was properly deleted. The actual issue may be that the deletion hasn't been flushed/committed before the new insert, or there's a race condition. Alternatively, the decline endpoint may not actually be called (the invitation is just ignored), leaving the old record in place.

### Bug 16: Presence/AFK — tab loses focus

`presence.js` starts a `setInterval(sendHeartbeat, 10000)` that runs continuously regardless of tab visibility. The `sendHeartbeat()` function sends `active: !isAllTabsIdle60s()` — so when the tab loses focus, cursor activity stops, but the heartbeat continues. After 60s of no cursor activity across all tabs, `active` becomes `false`, but the heartbeat itself keeps the Redis TTL alive (90s). The server sees heartbeats with `active=false` and computes AFK status, but only after 60s of inactivity. The issue is that heartbeats should stop or reduce frequency when the tab is hidden, allowing the Redis TTL to expire faster.

### Bug 17: Presence/AFK — tab close

When the last tab closes, `beforeunload` fires and `stopHeartbeat()` is called. No more heartbeats are sent. The Redis key has a 90s TTL. The `cleanupStalePresence()` scheduled task runs every 30s and checks for expired keys. The user should transition to OFFLINE after the TTL expires (~90s). The issue is that 90s is too long — users expect near-immediate offline detection. The TTL should be reduced to match the heartbeat interval more closely (e.g., 30-45s).

### Bug 18: Sidebar presence status display

`populateContacts()` calls `fetchInitialPresence(userIds)` and `subscribeToAllVisibleUsers()` after rendering contacts. The `subscribeToAllVisibleUsers()` is called with a 500ms delay. The issue is that `fetchInitialPresence()` calls `GET /api/presence?userIds=...` which should return current status, but the presence dots only update after navigating into a chat. This suggests either: (a) the fetch fails silently, (b) the response format doesn't match what `updatePresenceDot()` expects, or (c) the STOMP subscriptions are not established in time for the initial presence broadcast.

## Correctness Properties

Property 1: Bug Condition - DM Leave Prevention

_For any_ POST request to `/api/rooms/{roomId}/leave` where the room has `visibility == DIRECT`, the fixed `RoomMemberService.leaveRoom()` SHALL throw a `ForbiddenException` and prevent the user from leaving.

**Validates: Requirements 2.1**

Property 2: Bug Condition - WebSocket Reply-To Shows Correct Content

_For any_ message sent via WebSocket with a non-null `reply_to_id`, the fixed `ChatMessageHandler.sendMessage()` SHALL include `replyToSenderUsername` and `replyToContentSnippet` in the broadcast `ChatMessageResponse`, so that the reply preview displays the correct original message text immediately.

**Validates: Requirements 2.2**

Property 3: Bug Condition - Consistent Reply Quote on Delete

_For any_ message deletion where other messages reference the deleted message via `replyToId`, the fixed system SHALL render the reply quote consistently between live WebSocket state and server-rendered state after page refresh.

**Validates: Requirements 2.3**

Property 4: Bug Condition - No Duplicate Saved Messages

_For any_ user with a Saved Messages room, the fixed sidebar SHALL display only ONE "Saved Messages" entry — either the static button OR the list entry, not both.

**Validates: Requirements 2.4**

Property 5: Bug Condition - Contacts Show Correct Friend

_For any_ friendship displayed in the contacts list, the fixed `populateContacts()` SHALL consistently display the friend (not self) regardless of navigation timing or page state.

**Validates: Requirements 2.5**

Property 6: Bug Condition - Display Name Used in Chat Messages

_For any_ message where the sender has a non-null `displayName`, the fixed system SHALL display the display name in the chat message header instead of the username.

**Validates: Requirements 2.6**

Property 7: Bug Condition - Password Reset Returns 200

_For any_ POST request to `/api/password/reset` with a valid token and new password, the fixed system SHALL return HTTP 200 OK regardless of authentication status.

**Validates: Requirements 2.7**

Property 8: Bug Condition - Full Line Visibility Without Hover

_For any_ message rendered in the chat area, the fixed CSS SHALL display the full line content without requiring hover interaction to see it.

**Validates: Requirements 2.8**

Property 9: Bug Condition - Banned User Removed from Member List

_For any_ user banned from a channel via `banUserFromRoom()`, the fixed system SHALL remove the user from the `RoomMember` table so they no longer appear in the member list.

**Validates: Requirements 2.9**

Property 10: Bug Condition - Banned User Cannot View Channel

_For any_ banned user attempting to access a channel via `GET /chat/rooms/{id}`, the fixed system SHALL deny access and redirect or return an error.

**Validates: Requirements 2.10**

Property 11: Bug Condition - Banned User Cannot Send Messages

_For any_ banned user attempting to send a message to a channel, the fixed system SHALL reject the message and return an error.

**Validates: Requirements 2.11**

Property 12: Bug Condition - Ban List Returns Without Exception

_For any_ GET request to `/api/rooms/{roomId}/bans` by an authorized user, the fixed system SHALL return the ban list with usernames without throwing `LazyInitializationException`.

**Validates: Requirements 2.12**

Property 13: Bug Condition - Ban List Restricted to Owner/Admin

_For any_ non-owner/non-admin user attempting to access the ban list endpoint, the fixed system SHALL return HTTP 403 Forbidden.

**Validates: Requirements 2.13**

Property 14: Bug Condition - Re-Invitation After Decline Succeeds

_For any_ invitation sent to a user who previously declined an invitation to the same room, the fixed system SHALL handle the re-invitation gracefully without unique constraint violation.

**Validates: Requirements 2.14, 2.15**

Property 15: Bug Condition - Tab Focus Loss Triggers AFK Transition

_For any_ user who switches to a different browser tab, the fixed presence system SHALL stop sending heartbeats (or send with `active=false`) so that the user transitions to AFK status after the idle threshold.

**Validates: Requirements 2.16**

Property 16: Bug Condition - Tab Close Triggers Offline Transition

_For any_ user who closes their last browser tab, the fixed presence system SHALL ensure the user transitions to OFFLINE status within a reasonable timeframe (≤45 seconds).

**Validates: Requirements 2.17**

Property 17: Bug Condition - Sidebar Shows Correct Presence Status

_For any_ user viewing the sidebar, the fixed system SHALL display the correct online/offline/AFK status of contacts without requiring navigation into a chat.

**Validates: Requirements 2.18**

Property 18: Preservation - Existing Functionality Unchanged

_For any_ input where none of the 18 bug conditions hold, the fixed system SHALL produce the same behavior as the original system, preserving all existing functionality including room leaving for non-DM rooms, message sending, friend management, and UI interactions.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 3.11, 3.12, 3.13, 3.14**

## Fix Implementation

### Changes Required

**Bug 1 Fix — DM leave prevention**

**File**: `src/main/java/com/bovae/yac/service/RoomMemberService.java`

**Function**: `leaveRoom()`

**Changes**:
1. Add a check at the beginning of `leaveRoom()`: if `room.getVisibility() == RoomVisibility.DIRECT`, throw `ForbiddenException("Cannot leave a direct message room")`
2. This check should come before the existing owner check

---

**Bug 2 Fix — WebSocket reply-to showing "Original message deleted"**

**File**: `src/main/java/com/bovae/yac/ws/ChatMessageHandler.java`

**Function**: `sendMessage()`

**Changes**:
1. After resolving the `replyTo` message, extract `replyToSenderUsername` and `replyToContentSnippet` from the reply-to message
2. Pass these values into the `ChatMessageResponse` constructor instead of `null`:
   - `replyToSenderUsername = replyTo != null ? replyTo.getSender().getUsername() : null`
   - `replyToContentSnippet = replyTo != null ? (replyTo.getContent().length() > 100 ? replyTo.getContent().substring(0, 100) : replyTo.getContent()) : null`

---

**Bug 3 Fix — WebSocket message deletion reply quote inconsistency**

**Analysis**: The server-rendered state uses `th:unless="${msg.replyToSenderUsername() != null}"` to show "Original message deleted". When a message is deleted, `MessageService.toResponse()` returns `replyToSenderUsername = null` because the replyTo message no longer exists (or its sender is null). The live delete handler in `app.js` already updates reply quotes to "Original message deleted". The inconsistency is that after refresh, the reply quote should also show "Original message deleted" — which it does via the template logic. If the actual bug is that after refresh the reply quote disappears entirely, it's because the `replyTo` reference in the DB becomes a dangling FK (the message is deleted but the `reply_to_id` column still has the old ID). JPA returns `null` for the `replyTo` association when the referenced message doesn't exist.

**File**: `src/main/java/com/bovae/yac/service/MessageService.java`

**Function**: `toResponse()`

**Changes**:
1. When `message.getReplyTo()` is null but `message` has a non-null `reply_to_id` in the DB (dangling reference), the response should still indicate "Original message deleted" by setting `replyToId` to the original ID
2. Modify the query to preserve the `reply_to_id` value even when the referenced message is deleted. Use `@Query` with a native column read or add a `replyToId` field to Message entity that persists independently of the JPA relationship
3. Alternative simpler fix: In the `getMessageHistory` query, use LEFT JOIN FETCH for replyTo so that deleted messages result in null replyTo but the replyToId is still available from the message's column

**File**: `src/main/resources/static/js/app.js`

**Function**: `handleDeleteMessage()`

**Changes**:
1. The current behavior (showing "Original message deleted" on live delete) is correct
2. Ensure the server-rendered state matches by keeping `replyToId` populated in the response even when the referenced message is deleted

---

**Bug 4 Fix — Duplicate "Saved Messages" in sidebar**

**File**: `src/main/resources/static/js/sidebar.js`

**Function**: `createRoomItem()` or `populateRooms()`

**Changes**:
1. In `populateRooms()`, filter out the Saved Messages room from the direct chat list since it already has a dedicated button:
   ```javascript
   if (room.visibility === 'DIRECT' && !room.other_username && !room.other_display_name) {
     // Skip Saved Messages room — already has a dedicated button
     return;
   }
   ```
2. Add this filter inside the `rooms.forEach()` loop before appending to `directList`

---

**Bug 5 Fix — Contacts showing self instead of friend**

**File**: `src/main/resources/static/js/sidebar.js`

**Function**: `populateContacts()`

**Changes**:
1. The existing logic already has `if (String(friendId) === String(currentUserId)) { return; }` guard
2. The issue is likely that `getCurrentUserId()` returns undefined or a different format. Ensure `getCurrentUserId()` reliably returns the user ID as a string
3. Add defensive handling: if `currentUserId` is falsy, skip the self-filter but log a warning
4. Verify that `window.YAC_USER.id` is set correctly in the page template

---

**Bug 6 Fix — Display name not used in chats**

**File**: `src/main/java/com/bovae/yac/model/dto/ChatMessageResponse.java`

**Changes**:
1. Add a `senderDisplayName` field to the record:
   ```java
   public record ChatMessageResponse(
       UUID id, UUID roomId, UUID senderId,
       String senderUsername, String senderDisplayName,
       String content, UUID replyToId,
       String replyToSenderUsername, String replyToContentSnippet,
       boolean edited, Long watermark, Instant createdAt,
       List<AttachmentInfo> attachments
   ) {}
   ```

**File**: `src/main/java/com/bovae/yac/service/MessageService.java`

**Function**: `toResponse()`

**Changes**:
1. Add `message.getSender().getDisplayName()` to the response construction

**File**: `src/main/java/com/bovae/yac/ws/ChatMessageHandler.java`

**Function**: `sendMessage()`

**Changes**:
1. Include `sender.getDisplayName()` in the `ChatMessageResponse` constructor

**File**: `src/main/resources/templates/chat/room.html`

**Changes**:
1. Change message header from `msg.senderUsername()` to `msg.senderDisplayName() ?: msg.senderUsername()`
2. Change avatar initial to use display name first character if available

**File**: `src/main/resources/static/js/app.js`

**Function**: `onNewMessage()` (message rendering)

**Changes**:
1. Use `msg.sender_display_name || msg.senderDisplayName || msg.sender_username || msg.senderUsername` for the message header

---

**Bug 7 Fix — Password reset returning 302**

**File**: `src/main/java/com/bovae/yac/config/SecurityConfig.java`

**Changes**:
1. Add `/api/password/reset` and `/api/password/reset-request` to the `permitAll()` request matchers:
   ```java
   .requestMatchers(
       "/", "/login", "/register", "/forgot-password", "/reset-password",
       "/css/**", "/js/**", "/webjars/**",
       "/api/health", "/actuator/health",
       "/api/password/reset", "/api/password/reset-request",
       "/ws/**"
   ).permitAll()
   ```

---

**Bug 8 Fix — UI hover issue for full line visibility**

**File**: `src/main/resources/static/css/chat.css`

**Changes**:
1. Remove or adjust the `.message-item:hover` rule that causes layout shift:
   ```css
   .message-item:hover {
     background: #f0f2f5;
     border-radius: 0.375rem;
   }
   ```
2. Remove the `padding` and negative `margin` properties that cause content shift
3. The hover should only change background color without affecting layout

---

**Bug 9 Fix — Ban enforcement: remove from member list**

**File**: `src/main/java/com/bovae/yac/service/ModerationService.java`

**Function**: `banUserFromRoom()`

**Changes**:
1. After creating the ban record, also remove the user from the room membership (matching `kickMember()` behavior):
   ```java
   // Remove from membership if currently a member
   roomMemberRepository.findById(new RoomMemberId(room.getId(), targetUser.getId()))
       .ifPresent(roomMemberRepository::delete);
   ```

---

**Bug 10 Fix — Ban enforcement: prevent view access**

**File**: `src/main/java/com/bovae/yac/controller/web/ChatWebController.java`

**Function**: `roomView()`

**Changes**:
1. After resolving the room and user, add a ban check:
   ```java
   if (roomBanRepository.existsByRoomAndUser(room, user)) {
       throw new ForbiddenException("You are banned from this room");
   }
   ```
2. Inject `RoomBanRepository` into the controller

---

**Bug 11 Fix — Ban enforcement: prevent sending messages**

**File**: `src/main/java/com/bovae/yac/service/MessageService.java`

**Function**: `sendMessage()`

**Changes**:
1. After the `isMember` check, add a ban check for non-DIRECT rooms:
   ```java
   if (room.getVisibility() != RoomVisibility.DIRECT
       && roomBanRepository.existsByRoomAndUser(room, sender)) {
       throw new ForbiddenException("You are banned from this room");
   }
   ```
2. Inject `RoomBanRepository` into `MessageService`

---

**Bug 12 Fix — LazyInitializationException on ban list**

**File**: `src/main/java/com/bovae/yac/repository/RoomBanRepository.java`

**Changes**:
1. Add a custom query with JOIN FETCH:
   ```java
   @Query("SELECT rb FROM RoomBan rb JOIN FETCH rb.user JOIN FETCH rb.bannedBy WHERE rb.room = :room")
   List<RoomBan> findByRoomWithUserAndBannedBy(@Param("room") Room room);
   ```

**File**: `src/main/java/com/bovae/yac/controller/api/RoomBanApiController.java`

**Function**: `listBans()`

**Changes**:
1. Replace `roomBanRepository.findByRoom(room)` with `roomBanRepository.findByRoomWithUserAndBannedBy(room)`

---

**Bug 13 Fix — Ban list access restricted to owner/admin only**

**File**: `src/main/java/com/bovae/yac/controller/api/RoomBanApiController.java`

**Function**: `listBans()`

**Changes**:
1. After resolving the user and room, check that the user is the room owner or an admin:
   ```java
   User user = resolveUser(principal);
   Room room = roomService.getRoomById(roomId);

   if (!room.getOwner().getId().equals(user.getId())) {
       // Check if admin
       RoomMember member = roomMemberRepository.findById(new RoomMemberId(roomId, user.getId()))
           .orElseThrow(() -> new ForbiddenException("Access denied"));
       if (member.getRole() != RoomRole.ADMIN && member.getRole() != RoomRole.OWNER) {
           throw new ForbiddenException("Only room owners and admins can view the ban list");
       }
   }
   ```
2. Inject `RoomMemberRepository` into the controller

---

**Bug 14-15 Fix — Invitation re-send after decline**

**File**: `src/main/java/com/bovae/yac/controller/api/RoomInvitationApiController.java`

**Function**: `inviteUser()`

**Changes**:
1. Before creating a new invitation, check for and delete any existing invitation for the same (room, invitee) pair:
   ```java
   roomInvitationRepository.findByRoomAndInvitee(room, invitee)
       .ifPresent(roomInvitationRepository::delete);
   ```
2. This handles the case where a previous invitation exists (whether pending or not properly cleaned up)
3. Flush the delete before the save to avoid constraint violations within the same transaction
4. Add `@Transactional` to the method to ensure atomicity

---

**Bug 16 Fix — Presence/AFK: stop heartbeats when tab hidden**

**File**: `src/main/resources/static/js/presence.js`

**Changes**:
1. Add a `visibilitychange` listener that stops the heartbeat timer when the tab is hidden:
   ```javascript
   document.addEventListener('visibilitychange', function () {
     if (document.hidden) {
       stopHeartbeat();
     } else {
       startHeartbeat();
       sendImmediateActiveHeartbeat();
     }
   });
   ```
2. Modify the existing `visibilitychange` handler (which only handles the `!document.hidden` case) to also handle the `document.hidden` case by stopping heartbeats
3. This ensures that when a tab loses focus, heartbeats stop, and the Redis TTL will expire, transitioning the user to AFK/OFFLINE

---

**Bug 17 Fix — Presence/AFK: reduce TTL for faster offline detection**

**File**: `src/main/java/com/bovae/yac/service/PresenceService.java`

**Changes**:
1. Reduce `PRESENCE_TTL` from 90 seconds to 45 seconds:
   ```java
   private static final Duration PRESENCE_TTL = Duration.ofSeconds(45);
   ```
2. This ensures that after the last heartbeat (sent every 10s), the user transitions to OFFLINE within 45s maximum
3. With heartbeats stopping on tab hide (Bug 16 fix), the last heartbeat before tab close will expire in 45s

---

**Bug 18 Fix — Sidebar presence status display**

**File**: `src/main/resources/static/js/presence.js`

**Function**: `subscribeToAllVisibleUsers()` and initialization

**Changes**:
1. The issue is timing — `subscribeToAllVisibleUsers()` is called on DOMContentLoaded with a 2s delay, but STOMP may not be connected yet. The subscriptions need to wait for STOMP connection
2. Add a retry mechanism or hook into the STOMP `onConnect` callback:
   ```javascript
   // In stomp-client.js onConnect callback, trigger presence subscriptions
   if (window.YAC && window.YAC.presence && window.YAC.presence.subscribeToAllVisibleUsers) {
     window.YAC.presence.subscribeToAllVisibleUsers();
   }
   ```
3. Ensure `fetchInitialPresence()` is called AFTER the sidebar contacts are rendered (it already is, via `populateContacts()`)
4. The `fetchInitialPresence()` call in `populateContacts()` should work independently of STOMP — it uses a REST endpoint. Verify the `/api/presence` endpoint returns the correct format matching what `updatePresenceDot()` expects (`user_id` and `status` fields)

**File**: `src/main/resources/static/js/stomp-client.js`

**Function**: `subscribeToChannels()` (onConnect)

**Changes**:
1. After subscribing to room and notification channels, trigger presence subscriptions:
   ```javascript
   // Subscribe to presence for visible users after STOMP connects
   if (window.YAC && window.YAC.presence && window.YAC.presence.subscribeToAllVisibleUsers) {
     window.YAC.presence.subscribeToAllVisibleUsers();
   }
   ```

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bugs on unfixed code, then verify the fixes work correctly and preserve existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bugs BEFORE implementing fixes. Confirm or refute the root cause analysis.

**Test Plan**: Write tests that exercise each bug condition on the unfixed code to observe failures.

**Test Cases**:
1. **DM Leave Test**: Call POST `/api/rooms/{dmRoomId}/leave` and assert it fails (will succeed on unfixed code)
2. **WebSocket Reply-To Test**: Send message with reply_to_id via WebSocket, inspect broadcast payload for replyToSenderUsername (will be null on unfixed code)
3. **Duplicate Saved Messages Test**: Render sidebar for user with Saved Messages room, count "Saved Messages" entries (will show 2 on unfixed code)
4. **Display Name Test**: Send message as user with displayName, check rendered header (will show username on unfixed code)
5. **Password Reset 302 Test**: POST `/api/password/reset` without authentication, assert 200 (will get 302 on unfixed code)
6. **Ban Member List Test**: Ban user, fetch member list, assert banned user not present (will still be present on unfixed code)
7. **Ban View Access Test**: Ban user, access room as banned user, assert 403 (will get 200 on unfixed code)
8. **Ban Send Message Test**: Ban user, send message as banned user, assert error (will succeed on unfixed code)
9. **Ban List LazyInit Test**: Call GET `/api/rooms/{roomId}/bans`, assert 200 with data (will get 500 on unfixed code)
10. **Ban List Access Test**: Call GET `/api/rooms/{roomId}/bans` as non-owner, assert 403 (will get 200 on unfixed code)
11. **Re-Invitation Test**: Decline invitation, re-invite same user, assert 201 (will get 500 on unfixed code)
12. **Tab Hidden Heartbeat Test**: Simulate tab hidden, verify heartbeats stop (will continue on unfixed code)

**Expected Counterexamples**:
- Bug 1: 204 No Content (leave succeeds) instead of 403 Forbidden
- Bug 2: WebSocket broadcast has `replyToSenderUsername: null` causing "Original message deleted" display
- Bug 7: 302 Found redirect instead of 200 OK
- Bug 9: Banned user still in member list response
- Bug 12: 500 Internal Server Error with LazyInitializationException
- Bug 14: 500 with DataIntegrityViolationException on re-invitation

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := fixedFunction(input)
  ASSERT expectedBehavior(result)
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalFunction(input) = fixedFunction(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for non-bug inputs, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Leave Non-DM Room Preservation**: Verify leaving PUBLIC/PRIVATE rooms continues to work
2. **Message Send Preservation**: Verify normal message sending without reply-to continues to work
3. **Non-Banned User Access Preservation**: Verify non-banned users can still view rooms and send messages
4. **First-Time Invitation Preservation**: Verify first-time invitations still create records successfully
5. **Active Tab Heartbeat Preservation**: Verify heartbeats continue when tab is focused and user is active

### Unit Tests

- Test `RoomMemberService.leaveRoom()` throws ForbiddenException for DIRECT rooms
- Test `ChatMessageHandler.sendMessage()` includes reply-to metadata in broadcast
- Test `ModerationService.banUserFromRoom()` removes user from membership
- Test `RoomBanApiController.listBans()` returns data without exception
- Test `RoomBanApiController.listBans()` returns 403 for non-owner
- Test `RoomInvitationApiController.inviteUser()` handles re-invitation after decline
- Test `SecurityConfig` permits `/api/password/reset` without authentication
- Test `MessageService.sendMessage()` rejects banned users

### Property-Based Tests

- Generate random room visibilities and verify leave is only blocked for DIRECT rooms
- Generate random message+reply combinations and verify WebSocket broadcast always includes reply metadata
- Generate random ban/membership states and verify banned users cannot access or send messages
- Generate random invitation sequences (invite, decline, re-invite) and verify no constraint violations
- Generate random presence states and verify correct status computation

### Integration Tests

- Test full DM leave prevention flow (create DM, attempt leave, verify failure)
- Test full ban enforcement flow (ban user, verify member list, verify access denied, verify send denied)
- Test full invitation lifecycle (invite, decline, re-invite, accept)
- Test password reset flow end-to-end without authentication
- Test WebSocket reply-to rendering with live message and page refresh comparison
