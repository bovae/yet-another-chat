# Yet Another Chat (YAC)

![Build](https://img.shields.io/github/actions/workflow/status/bovae/yet-another-chat/build-and-test.yml?branch=develop&label=Build)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue)
![Redis](https://img.shields.io/badge/Redis-7-red)

Classic web-based online chat application with rooms, contacts, file sharing, and real-time presence.

## Tech Stack

- Java 21, Spring Boot 4.0.5, Spring MVC
- PostgreSQL 17, Redis 7
- HTMX 2.0 + Thymeleaf + Bootstrap 5.3 (WebJars, no Node.js)
- STOMP over WebSocket for real-time messaging
- Spring Security (session-based auth, BCrypt)
- Spring Session Redis (multi-device support)
- Liquibase for database migrations
- Testcontainers for integration tests

## Quick Start

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080)

## Development

Start infrastructure only:

```bash
make infra
```

Run the app from your IDE or:

```bash
make run
```

## Useful Commands

| Command | Description |
|---|---|
| `make build` | Build without tests |
| `make run` | Run with Spring Boot |
| `make test` | Run all tests (Testcontainers) |
| `make infra` | Start Postgres + Redis only |
| `make docker-up` | Build and start all containers |
| `make docker-down` | Stop containers |
| `make docker-logs` | Tail app container logs |
| `make clean` | Clean build + remove volumes |

## Configuration

Key environment variables (with defaults for local dev):

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `yac` | Database name |
| `DB_USER` | `yac` | Database user |
| `DB_PASSWORD` | `yac` | Database password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REMEMBER_ME_KEY` | _(required — no default)_ | Secret for remember-me tokens; startup fails if unset or blank |
| `WEBSOCKET_ALLOWED_ORIGINS` | `http://localhost:8080` | Comma-separated allowed WebSocket origins |
| `FILE_STORAGE_PATH` | `./file-storage` | Directory for uploaded files |

## Deployment Constraints

**Single-node only.** The application currently runs as a single instance and cannot be
horizontally scaled as-is:

- **STOMP broker** — messages are routed through Spring's in-memory simple broker. A second
  instance would not see topics/queues created on the first. Multi-node requires an external
  STOMP relay (e.g. RabbitMQ/ActiveMQ) via `enableStompBrokerRelay`.
- **Presence state** — per-session presence lives in Redis, but the transition-detection map
  (`lastKnownStatus`) and the sweep are per-JVM. Running two instances would double-broadcast
  transitions and miss cross-node sessions.

Redis (sessions) and PostgreSQL are shared and safe to point multiple instances at; the broker
and presence coordination are the blockers. Do not run more than one instance.
