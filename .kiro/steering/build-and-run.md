---
inclusion: manual
---

# Build, Run & Deploy

## Prerequisites

- Java 21 (Amazon Corretto recommended)
- Docker (for Testcontainers and local infrastructure)
- Maven wrapper included (`./mvnw`) — no global Maven install needed

## Local Development

Start infrastructure (Postgres + Redis), then run the app:

```bash
make infra           # docker compose up postgres redis -d
make run             # ./mvnw spring-boot:run
```

App runs at `http://localhost:8080`. Default DB credentials: `yac/yac/yac` on `localhost:5432`.

## Build

```bash
make build           # ./mvnw clean package -DskipTests
```

Produces `target/yet-another-chat-0.0.1-SNAPSHOT.jar`.

## Tests

```bash
make test            # ./mvnw clean verify (all tests, requires Docker for Testcontainers)
./mvnw test          # Unit + parameterized tests only
./mvnw verify        # Full suite including integration tests
```

Tests spin up PostgreSQL 17 and Redis 7 containers via Testcontainers automatically — no manual infra setup needed.

## Docker Compose (Full Stack)

```bash
make docker-up       # docker compose up --build -d (app + postgres + redis)
make docker-down     # docker compose down
make docker-logs     # docker compose logs -f app
make clean           # ./mvnw clean + docker compose down -v (removes volumes)
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `yac` | Database name |
| `DB_USER` | `yac` | Database user |
| `DB_PASSWORD` | `yac` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REMEMBER_ME_KEY` | `change-me-in-production` | Spring Security remember-me key |
| `FILE_STORAGE_PATH` | `./file-storage` | File upload storage directory |
| `WEBSOCKET_ALLOWED_ORIGINS` | `http://localhost:8080` | CORS origins for WebSocket |

## Database Migrations

Liquibase runs automatically on startup. Changelog: `src/main/resources/db/changelog/db.changelog-master.yaml`.

JPA `ddl-auto` is set to `validate` — schema changes must go through Liquibase migration files, never Hibernate auto-DDL.

## Key Ports

- App: `8080`
- PostgreSQL: `5432`
- Redis: `6379`
