# ui-e2e-tests Specification

## Purpose

Automated browser E2E coverage for the chat UI, established by the `address-r2-r3-findings` change (R3-01, user request). Guards the real-time, presence, and social-flow regressions found in R1–R3.

## ADDED Requirements

### Requirement: Playwright-for-Java E2E suite behind its own Maven profile (R3-01)
The project SHALL provide automated UI tests using Playwright-for-Java (`com.microsoft.playwright:playwright`, JVM-only — no Node.js runtime), activated only by a dedicated Maven profile (e.g. `-Pe2e`) with its own `*E2E` naming/failsafe binding, so the default `verify` and fast unit builds are unaffected.

#### Scenario: Default build unaffected
- **WHEN** `./mvnw verify` runs without the e2e profile
- **THEN** no browser test executes and no browser binaries are required

#### Scenario: E2E profile runs the suite
- **WHEN** `./mvnw verify -Pe2e` runs against a running app
- **THEN** the `*E2E` classes execute in real browsers and report pass/fail

### Requirement: E2E scenarios cover reviewed regressions (R3-01)
The suite SHALL cover, at minimum, each flow flagged by R1–R3 reviews: register → login → persistent session; live two-context messaging (send, edit, delete propagation); image upload rendering live for the other user; presence ONLINE-on-load and idle→AFK; live friend-request arrival and accept; Saved Messages and DM naming; attachment ACL (non-member denied); admin kick/ban/unban via the modal.

#### Scenario: Two-browser live messaging
- **WHEN** the suite runs user A and user B in separate browser contexts and A sends a message
- **THEN** the test asserts B sees it live without a reload (guards R1-01/03/04/05)

#### Scenario: Presence on load
- **WHEN** a context loads the chat page and performs no input
- **THEN** the test asserts the user reads ONLINE (guards R2-01)
