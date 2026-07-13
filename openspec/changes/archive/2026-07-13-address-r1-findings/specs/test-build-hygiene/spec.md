# test-build-hygiene — Delta Spec

Covers R1-18, R1-52…54, R1-74, R1-75.

## ADDED Requirements

### Requirement: Property-based tests removed, unique coverage ported (R1-18)
All jqwik property-based tests (47 files under `src/test/java/com/bovae/yac/property/`) SHALL be removed, along with the jqwik/jqwik-spring dependencies and the `.jqwik-database` artifacts. Before removal, the ten uniquely-covered behaviours listed in the catalog (room-update endpoint contract, reply-to/quote fields, presence batch endpoint, UNREAD_UPDATE fan-out count, DM presentation, friend-request filtering + `request_text`, message-DTO attachment metadata, room-view controller guards, misc leave/re-invite/display-name rules, camelCase-key-rejected contract) SHALL be ported as plain JUnit tests into existing unit/IT classes.

#### Scenario: Build after removal
- **WHEN** `./mvnw verify` runs after the change
- **THEN** no `@Property` tests execute, jqwik is absent from the dependency tree, and the ten ported behaviours are covered by plain tests

### Requirement: Tests assert behaviour, not source text (R1-52)
Tests SHALL NOT read production source files and assert on their string contents; the five such tests in `BugConditionExplorationIT` SHALL be deleted (bug1/bug6 MockMvc tests stay).

#### Scenario: Refactoring production code
- **WHEN** a production method is renamed without behaviour change
- **THEN** no test fails due to source-text assertions

### Requirement: WebSocket tests are event-driven (R1-53)
WebSocket integration tests SHALL await on queues/conditions with timeouts instead of fixed `Thread.sleep` calls.

#### Scenario: Fast machine
- **WHEN** the WS ITs run on a fast machine
- **THEN** they complete as soon as events arrive, with no fixed 500–2000 ms sleeps

### Requirement: Content-Disposition covered by integration test (R1-54)
`AttachmentApiIT` SHALL assert the `Content-Disposition` header for image and non-image downloads (ties to R1-13's always-attachment policy).

#### Scenario: Image download header
- **WHEN** the IT downloads an uploaded image
- **THEN** it asserts `Content-Disposition` is `attachment` with the encoded filename

### Requirement: Integration tests run in the failsafe phase (R1-74)
`@SpringBootTest`+Testcontainers classes SHALL be named `*IT` (rename `RestApiIntegrationTest`, `PresenceNotificationIntegrationTest`); the duplicate `HealthApiControllerTest` SHALL be deleted.

#### Scenario: Unit phase stays fast
- **WHEN** `./mvnw test` runs
- **THEN** no Testcontainers-backed class executes in the surefire phase

### Requirement: CI has timeout and concurrency cancel (R1-75)
The build workflow SHALL set `timeout-minutes` and a `concurrency` group with `cancel-in-progress: true`.

#### Scenario: Stale run superseded
- **WHEN** a new push lands while a previous CI run is in progress on the same ref
- **THEN** the older run is cancelled
