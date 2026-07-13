# Bugfix Requirements Document

## Introduction

When a user clicks "Join" on a public room in the room catalog (`/rooms/catalog`), they are redirected to the room chat view (`/chat/rooms/{id}`) but are not added as a room member. The "Join" button is implemented as a plain HTML anchor link that navigates directly to the chat view without invoking the join API (`POST /api/rooms/{id}/join`). As a result, no `RoomMember` entry is created, and the user cannot send messages in the room.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a non-member user clicks "Join" on a public room in the catalog THEN the system navigates directly to the chat room view at `/chat/rooms/{id}` without creating a `RoomMember` entry for the user

1.2 WHEN a non-member user is redirected to the chat room view via the catalog "Join" button THEN the system displays messages but the user cannot send messages because they are not a room member

1.3 WHEN a non-member user clicks "Join" on a public room in the catalog THEN the system does not call the `RoomMemberService.joinPublicRoom()` method and no membership record is persisted

### Expected Behavior (Correct)

2.1 WHEN a non-member user clicks "Join" on a public room in the catalog THEN the system SHALL create a `RoomMember` entry with `MEMBER` role for the user in that room before redirecting to the chat view

2.2 WHEN a non-member user joins a public room via the catalog "Join" button THEN the system SHALL redirect the user to the chat room view where they can both see messages AND send messages

2.3 WHEN a non-member user clicks "Join" on a public room in the catalog THEN the system SHALL invoke the room membership join logic (equivalent to `POST /api/rooms/{id}/join`) so that the membership is persisted in the database

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a user is already a member of a room THEN the system SHALL CONTINUE TO allow them to access the room and send messages normally

3.2 WHEN a user joins a public room via the REST API (`POST /api/rooms/{id}/join`) THEN the system SHALL CONTINUE TO create a `RoomMember` entry and return a success response

3.3 WHEN a user who is banned from a room attempts to join THEN the system SHALL CONTINUE TO reject the join attempt with a forbidden error

3.4 WHEN a user browses the room catalog and searches for rooms THEN the system SHALL CONTINUE TO display the catalog with room listings, member counts, and search functionality

3.5 WHEN a room owner or existing member navigates to `/chat/rooms/{id}` THEN the system SHALL CONTINUE TO display the chat view with messages and allow sending messages

---

## Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type CatalogJoinAction
  OUTPUT: boolean

  // The bug triggers when a non-member user clicks "Join" in the catalog
  RETURN X.user IS NOT member OF X.room
     AND X.room.visibility = PUBLIC
     AND X.action = "catalog_join_click"
END FUNCTION
```

## Fix Checking Property

```pascal
// Property: Fix Checking — Catalog Join creates membership
FOR ALL X WHERE isBugCondition(X) DO
  result ← catalogJoinAction'(X)
  ASSERT X.user IS member OF X.room WITH role = MEMBER
     AND result.redirectsTo = "/chat/rooms/" + X.room.id
     AND X.user CAN send messages IN X.room
END FOR
```

## Preservation Checking Property

```pascal
// Property: Preservation Checking — Existing behavior unchanged
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT F(X) = F'(X)
END FOR
```

This ensures that for all non-buggy inputs (existing members accessing rooms, API-based joins, banned user rejections, catalog browsing), the fixed code behaves identically to the original.
