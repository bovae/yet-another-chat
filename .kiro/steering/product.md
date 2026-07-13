# Product Context — Yet Another Chat (YAC)

YAC is a classic web-based online chat application with rooms, contacts, file sharing, and real-time presence.

## Core Features

- **Authentication**: Email/password registration and login, session-based auth (Spring Security + BCrypt), remember-me tokens, multi-device session management, password reset flow
- **Chat Rooms**: Public rooms (searchable catalog, anyone can join), private rooms (invite-only), direct messages (1:1 between friends). Rooms have an owner and optional admins
- **Messaging**: Real-time text messages via STOMP WebSocket, message editing and deletion, cursor-based paginated history, watermark-based ordering, reply-to threading
- **File Sharing**: Upload attachments to messages (max 20MB per file, 3MB for images), download with access control, cascade delete with parent message
- **Social**: Friend requests (send/accept/decline/remove), user blocking (prevents DMs and friend requests)
- **Moderation**: Room owners and admins can kick members (creates a ban), grant/revoke admin role, delete messages. Room bans prevent rejoining
- **Presence**: Heartbeat-based online status (ONLINE/AFK/OFFLINE) with 30-second TTL in Redis, broadcast to room members
- **Notifications**: Per-room unread message counts, watermark-based read tracking, real-time broadcast via WebSocket

## Domain Entities

- **User**: email (unique), username (unique), displayName, passwordHash
- **Room**: name (unique), description, visibility (PUBLIC/PRIVATE/DIRECT), owner, nextWatermark
- **RoomMember**: room + user (composite key), role (OWNER/ADMIN/MEMBER)
- **Message**: room, sender, content (max 3072 UTF-8 bytes), replyTo, edited flag, watermark
- **Attachment**: message, originalFileName, storagePath, fileSize, contentType
- **Friendship**: requester, recipient, status (PENDING/ACCEPTED/DECLINED), requestText
- **UserBan**: blocker, blocked
- **RoomBan**: room, user, bannedBy
- **UnreadMarker**: user + room (composite key), lastReadWatermark, unreadCount
- **RoomInvitation**: room, inviter, invitee
- **PasswordResetToken**: user, tokenHash, used, expiresAt

## Key Business Rules

- Room names are globally unique
- Only room owners can delete rooms; owners and admins can moderate
- Friendship is bidirectional — either party can remove it
- User bans block both directions (DMs and friend requests)
- Room deletion cascades: unread markers → invitations → bans → members → attachments → messages → room
- Messages use monotonically increasing watermarks per room for ordering
- Presence status is computed from Redis TTL: heartbeat within 30s = ONLINE, expired = OFFLINE
