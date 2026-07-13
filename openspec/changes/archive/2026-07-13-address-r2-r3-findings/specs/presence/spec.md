# presence — Delta Spec

Covers R2-01.

## ADDED Requirements

### Requirement: Fresh tab reports ONLINE without user input (R2-01)
A freshly loaded or newly focused tab SHALL report the user as ONLINE immediately: page load and focus count as activity, and an active heartbeat SHALL be sent as soon as the STOMP connection is established (not after the first mouse/keyboard event). The user SHALL only become AFK after the 60s idle window elapses with no interaction in any tab (spec 2.2.2).

#### Scenario: Just-loaded focused tab
- **WHEN** a user loads the chat page and touches nothing
- **THEN** other users see them ONLINE within the presence-propagation latency, and `GET /api/presence` reports ONLINE — flipping to AFK only after 60s of no interaction

#### Scenario: Tab regains focus
- **WHEN** an idle (AFK) user focuses the tab again
- **THEN** an immediate active heartbeat is sent and their status returns to ONLINE without requiring mouse movement
