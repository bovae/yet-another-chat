# authorization-hardening Specification

## Purpose

Specification for the `authorization-hardening` capability, established by the `address-r1-findings` change. Covers R1-08…15, R1-25, R1-32…36, R1-56, R1-64, R1-66, R1-67.

## Requirements

### Requirement: Message history requires membership (R1-08)
`GET /api/rooms/{id}/messages` SHALL require room membership; non-members may read only PUBLIC rooms. Kicked/banned users lose read access.

#### Scenario: Non-member reads a private room
- **WHEN** an authenticated non-member requests a private room's history by UUID
- **THEN** the response is 403/404, not the messages

### Requirement: Room-topic SUBSCRIBE requires membership (R1-09)
STOMP SUBSCRIBE frames to `/topic/room.*` SHALL be authorized against room membership (public rooms follow the same visibility rule as history reads).

#### Scenario: Eavesdrop on a private room topic
- **WHEN** an authenticated non-member subscribes to `/topic/room.{privateRoomId}`
- **THEN** the subscription is rejected and no messages are delivered

### Requirement: Password-reset token delivered out-of-band (R1-10)
The reset token SHALL never be rendered in any HTTP response. It SHALL be sent by email (local Mailpit/MailHog container for dev). Both web and API reset requests SHALL always answer with the same generic success message, and the API path SHALL actually send the token.

#### Scenario: Attacker requests reset for a victim
- **WHEN** anyone submits the forgot-password form with a victim's email
- **THEN** the page shows only a generic message and the reset link exists only in the victim's mailbox

### Requirement: Session termination scoped to owner (R1-11)
`DELETE`/terminate on a session SHALL verify the session's principal equals the caller; otherwise 404.

#### Scenario: Terminating another user's session
- **WHEN** a user submits another user's session id
- **THEN** the response is 404 and the session stays valid

### Requirement: Room invitations require inviter privilege (R1-12)
Creating an invitation SHALL require the inviter to be an OWNER/ADMIN member of the room (matching the UI's affordance).

#### Scenario: Self-invite into a private room
- **WHEN** a non-member posts an invitation to a private room naming themselves as invitee
- **THEN** the request is rejected with 403

### Requirement: Uploaded content cannot execute in the app origin (R1-13)
Attachment downloads SHALL be served with `Content-Disposition: attachment` (images still render via `<img>`), and stored content type SHALL be validated server-side rather than trusted from the client.

#### Scenario: SVG with embedded script
- **WHEN** a user uploads an SVG containing `<script>` and a victim opens its download URL
- **THEN** the browser downloads the file; no script executes in the app origin

### Requirement: Uploaded filenames sanitized against traversal (R1-14)
Stored filenames SHALL be reduced to their last path segment and the resolved target path SHALL be verified to stay within the room's storage directory.

#### Scenario: Traversal filename
- **WHEN** a multipart filename `a/../../../etc/target` is uploaded
- **THEN** the file is stored inside the room directory under a sanitized name; nothing outside is written

### Requirement: Attachment download scoped to its room (R1-15)
Download SHALL verify the attachment's message belongs to the room in the URL path, in addition to the caller's membership of that room.

#### Scenario: Cross-room attachment fetch
- **WHEN** a user requests another room's attachment UUID through a room they belong to
- **THEN** the response is 404

### Requirement: Message deletion scoped to its room (R1-25)
Admin message deletion SHALL verify the message belongs to the room named in the URL before applying room-role authorization.

#### Scenario: Admin of room A deletes a message in room B
- **WHEN** an OWNER of room A calls delete with room A in the path and a room-B message id
- **THEN** the response is 404 and the message survives

### Requirement: Upload bound to the uploader's own message in the room (R1-32)
Attachment upload SHALL verify the target message belongs to the path room and was authored by the uploader.

#### Scenario: Attaching to a foreign message
- **WHEN** a member uploads a file naming another user's message id
- **THEN** the request is rejected

### Requirement: Content-Disposition built safely (R1-33)
The download header SHALL be built with `ContentDisposition.builder(...).filename(name, UTF_8)`, never by string concatenation of the client-supplied filename.

#### Scenario: Filename with quote/CRLF
- **WHEN** an attachment named `a".txt\r\nX: y` is downloaded
- **THEN** the header is well-formed with the filename properly encoded

### Requirement: Size caps enforced by sniffed media type (R1-34)
The 3 MB image cap SHALL be decided by content sniffing (magic bytes), not the client-supplied content type.

#### Scenario: Oversized image mislabeled
- **WHEN** a 5 MB PNG is uploaded declared as `application/octet-stream`
- **THEN** the upload is rejected against the 3 MB image cap

### Requirement: Remember-me key required at startup (R1-35)
The application SHALL fail startup when `REMEMBER_ME_KEY` is missing or blank; no hardcoded default. The literal dev value lives only in `docker-compose.yml`.

#### Scenario: Deployment without the key
- **WHEN** the app starts with no `REMEMBER_ME_KEY`
- **THEN** startup fails with a clear configuration error

### Requirement: Password reset/change invalidates other sessions (R1-36)
Password reset and change SHALL delete the user's other persisted sessions and invalidate remember-me tokens.

#### Scenario: Reset after compromise
- **WHEN** a user resets their password
- **THEN** an attacker's previously established session/cookie no longer works

### Requirement: Member list requires membership (R1-56)
The room member-list endpoint SHALL require membership (public rooms follow catalog visibility).

#### Scenario: Enumerating a private room
- **WHEN** an authenticated non-member requests a private room's member list
- **THEN** the response is 403/404

### Requirement: Invitation decline/cancel restricted (R1-64)
Declining/cancelling an invitation SHALL be limited to the invitee, the inviter, or a room admin.

#### Scenario: Third party cancels an invitation
- **WHEN** an unrelated user calls decline on someone else's invitation
- **THEN** the request is rejected

### Requirement: Password policy enforced (R1-66)
All password inputs (registration, reset, change) SHALL enforce a minimum length of 8 characters.

#### Scenario: One-character password
- **WHEN** a user registers with password "x"
- **THEN** the request fails validation with a clear message

### Requirement: Presence query input validated (R1-67)
`GET /api/presence` SHALL return 400 (not 500) on malformed UUIDs and SHALL bound the number of ids per request.

#### Scenario: Malformed UUID
- **WHEN** the `userIds` parameter contains "not-a-uuid"
- **THEN** the response is 400
