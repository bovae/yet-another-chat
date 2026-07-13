# presence Specification

## Purpose

Specification for the `presence` capability, established by the `address-r1-findings` change. Covers R1-38…43, R1-59, R1-60, R1-61, R1-65.

## Requirements

### Requirement: Multi-device presence aggregation (R1-38)
Presence SHALL be tracked per session/device; a user's status is ONLINE if any device is active (no last-writer-wins flapping between devices).

#### Scenario: Active on one device, idle on another
- **WHEN** a user is active on device A while device B idles
- **THEN** subscribers see a stable ONLINE status, not ONLINE↔AFK flapping

### Requirement: Presence cleanup on disconnect (R1-39)
When a user's last WebSocket session closes (disconnect or logout), their presence SHALL be removed immediately rather than waiting for TTL expiry.

#### Scenario: Browser closed
- **WHEN** a user closes their last tab
- **THEN** their status becomes OFFLINE promptly (no ~75s phantom ONLINE)

### Requirement: Typing events require room membership (R1-40)
The typing handler SHALL verify the sender is a member of the room before broadcasting a TYPING event.

#### Scenario: Non-member injects typing
- **WHEN** an authenticated non-member sends a typing event for a room UUID
- **THEN** nothing is broadcast to that room

### Requirement: Hidden tab means AFK, not OFFLINE (R1-41)
A hidden/minimized tab SHALL keep sending reduced-rate heartbeats with `active:false`, so a user with only hidden tabs shows AFK, and OFFLINE only when all tabs are closed (spec 2.2.3).

#### Scenario: Single minimized tab
- **WHEN** a user's only tab stays hidden for over a minute
- **THEN** their status is AFK, not OFFLINE

### Requirement: Throttled activity broadcasts (R1-42)
Cross-tab activity signalling (mousemove/keydown) SHALL be throttled to at most ~1 event per second.

#### Scenario: Continuous mouse movement
- **WHEN** the user moves the mouse continuously
- **THEN** at most ~1 cross-tab broadcast per second is emitted

### Requirement: Single-node constraint documented (R1-43)
The single-node deployment ceiling (in-memory simple broker + in-memory presence state) SHALL be documented as a hard constraint in the project README/ops notes. No broker relay is introduced.

#### Scenario: Operator reads deployment docs
- **WHEN** an operator considers running a second instance
- **THEN** the documentation states that multi-node requires a STOMP relay and shared presence state

### Requirement: Presence sweep avoids blocking Redis scans (R1-59)
The periodic presence sweep SHALL use incremental `SCAN` (or a maintained active-user set) instead of `KEYS`.

#### Scenario: Sweep under load
- **WHEN** the 30s sweep runs against a Redis holding all HTTP sessions
- **THEN** no blocking full-keyspace `KEYS` command is issued

### Requirement: AFK computation has no unreachable branch (R1-60)
Server-side status computation SHALL contain no dead branch: either the dead `AFK_THRESHOLD` branch is removed (client enforces the 60s idle rule) or the TTL is raised above the threshold so the branch is reachable. A fresh `active:false` heartbeat within the threshold SHALL NOT map to AFK (pairs with R2-01 semantics).

#### Scenario: Recently active user with idle heartbeat
- **WHEN** a heartbeat with `active:false` arrives within the 60s idle window
- **THEN** the computed status is ONLINE

### Requirement: Atomic presence-change detection (R1-61)
Status-transition detection SHALL be atomic (single map operation) so concurrent heartbeats/sweeps cannot double-broadcast or drop a transition.

#### Scenario: Concurrent heartbeat and sweep
- **WHEN** a heartbeat and the sweep evaluate the same user concurrently
- **THEN** exactly one transition event is broadcast

### Requirement: Presence visibility restricted (R1-65)
Presence subscription (`/topic/presence.{userId}`) and query (`GET /api/presence`) SHALL be limited to the user's friends and co-members, not any authenticated user.

#### Scenario: Stranger tracks presence
- **WHEN** an authenticated user with no shared room or friendship subscribes to another user's presence topic
- **THEN** the subscription is rejected
