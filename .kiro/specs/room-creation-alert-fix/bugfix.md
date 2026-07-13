# Bugfix Requirements Document

## Introduction

Two related UI bugs affect the room creation flow and general user experience in YAC. First, creating a room via the UI shows a false "Failed to create room" error alert even though the room is created successfully — caused by the JavaScript attempting to parse a JSON body from an HTTP 201 response that has no body. Second, all `alert()` and `confirm()` calls across the frontend use native browser dialogs instead of Bootstrap modals, resulting in an inconsistent and jarring UX that doesn't match the rest of the application's design.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a user submits the room creation form and the API returns HTTP 201 with no response body THEN the system shows a false "Failed to create room" alert because `resp.json()` throws on the empty body, triggering the `.catch()` handler

1.2 WHEN a user submits the room creation form and the API returns HTTP 201 with no response body THEN the system does not redirect the user to the newly created room's chat page because the response contains no room details to extract the ID from

1.3 WHEN an API error occurs during room creation (e.g., duplicate name) THEN the system displays the error using a native browser `alert()` dialog instead of a Bootstrap modal

1.4 WHEN a room admin clicks "kick member" or "ban member" THEN the system uses a native browser `confirm()` dialog for the confirmation prompt instead of a Bootstrap modal

1.5 WHEN an admin action (kick, ban, promote, demote) fails with an API error THEN the system displays the error using a native browser `alert()` dialog instead of a Bootstrap modal

1.6 WHEN room deletion fails with an API error THEN the system displays the error using a native browser `alert()` dialog instead of a Bootstrap modal

### Expected Behavior (Correct)

2.1 WHEN a user submits the room creation form and the API returns HTTP 201 THEN the system SHALL receive a JSON response body containing the full created room details (id, name, description, visibility) and redirect the user to `/chat/rooms/{id}` using the id from the response

2.2 WHEN an API error occurs during room creation THEN the system SHALL display the error message in a Bootstrap modal dialog consistent with the application's existing modal patterns

2.3 WHEN a room admin clicks "kick member" or "ban member" THEN the system SHALL display a Bootstrap confirmation modal (with Cancel and Confirm buttons) instead of a native browser `confirm()` dialog

2.4 WHEN an admin action (kick, ban, promote, demote) fails with an API error THEN the system SHALL display the error message in a Bootstrap modal dialog instead of a native browser `alert()`

2.5 WHEN room deletion fails with an API error THEN the system SHALL display the error message in a Bootstrap modal dialog instead of a native browser `alert()`

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a user submits the room creation form with a duplicate room name THEN the system SHALL CONTINUE TO return an appropriate error response from the API

3.2 WHEN a room admin successfully kicks or bans a member THEN the system SHALL CONTINUE TO reload the page after the action completes

3.3 WHEN a room admin successfully promotes or demotes a member THEN the system SHALL CONTINUE TO reload the page after the action completes

3.4 WHEN a room owner successfully deletes a room THEN the system SHALL CONTINUE TO redirect to `/chat`

3.5 WHEN a user sends an invitation successfully THEN the system SHALL CONTINUE TO display the success feedback inline in the invite modal

3.6 WHEN the delete room confirmation modal is shown THEN the system SHALL CONTINUE TO use the existing `deleteRoomModal` from `admin-modals.html`
