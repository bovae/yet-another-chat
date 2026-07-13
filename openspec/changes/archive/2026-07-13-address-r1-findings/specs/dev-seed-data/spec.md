# dev-seed-data — Delta Spec

Covers R1-55.

## ADDED Requirements

### Requirement: Dev-only seed migration (R1-55)
A Liquibase changeset (`context:dev`) SHALL seed local users, rooms, and memberships with fixed UUIDs, precomputed BCrypt password literals, and `ON CONFLICT DO NOTHING` idempotency. Liquibase contexts SHALL be set explicitly on both sides — `spring.liquibase.contexts: prod` in `application.yml`, `dev` in `application-dev.yml` — so the seed never runs in prod or Testcontainers tests (empty-context pitfall).

#### Scenario: Local dev startup
- **WHEN** the app starts with the `dev` profile against the persisted local volume
- **THEN** seed users/rooms exist exactly once and login works with the documented dev passwords

#### Scenario: Test and prod startup
- **WHEN** Testcontainers tests or a prod-profile instance run migrations
- **THEN** no seed rows are inserted
