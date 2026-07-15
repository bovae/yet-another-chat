# AGENTS.md — Yet Another Chat (YAC)

Guidance for AI coding agents working in this repo. Consolidates `.kiro/steering/*`.

## What This Is

Classic web-based chat app: rooms, direct messages, contacts, file sharing, real-time
presence. Server-rendered (Thymeleaf + HTMX), real-time over STOMP/WebSocket, single-node.

**Package root:** `com.bovae.yac` · **App entry:** `YacApplication.java`

## Tech Stack

- **Java 21** (Amazon Corretto) — use records, text blocks, pattern matching, sealed classes where they fit
- **Spring Boot 4.0.5** — webmvc, data-jpa, data-redis, security, websocket, thymeleaf, session-data-redis, liquibase, actuator, validation
- **PostgreSQL 17** (JPA/Hibernate, `ddl-auto: validate`) — schema owned by **Liquibase**, never Hibernate auto-DDL
- **Redis 7** — caching, TTL-based presence, pub/sub, Spring Session (namespace `spring:session:yac`, 30-day timeout)
- **Frontend:** Thymeleaf + HTMX 2.0 + Bootstrap 5.3, STOMP.js — all via **WebJars, no Node/npm**
- **Auth:** Spring Security session-based, BCrypt, form login, remember-me, CSRF
- **Build:** Maven wrapper (`./mvnw`), Lombok 1.18.44 (logger field is `LOG`)
- **Tests:** JUnit 5, Testcontainers 1.21.4 (real Postgres+Redis), MockMvc, Mockito, jqwik 1.9.3

## Build / Run / Test Commands

Prereqs: Java 21, Docker (for Testcontainers + local infra). Maven wrapper is bundled.

```bash
make infra        # docker compose up postgres redis -d  (local infra only)
make run          # ./mvnw spring-boot:run                (app at http://localhost:8080)
make build        # ./mvnw clean package -DskipTests       (→ target/yet-another-chat-0.0.1-SNAPSHOT.jar)
make format       # ./mvnw spotless:apply                  (auto-fix formatting)
make lint         # ./mvnw verify -DskipTests              (static analysis, skips tests)
make test         # ./mvnw clean verify                    (ALL tests, needs Docker)
make docker-up    # docker compose up --build -d           (full stack: app+pg+redis)
make docker-down  # docker compose down
make docker-logs  # docker compose logs -f app
make clean        # ./mvnw clean + docker compose down -v  (removes volumes)
```

Direct Maven:

```bash
./mvnw test       # unit + parameterized tests only
./mvnw verify     # full suite incl. integration tests (Testcontainers)
```

### Static analysis gate (runs automatically)

**spotless, checkstyle, pmd/cpd** bind to the `validate` phase — they fire on every
`package`/`verify`/`build`, not just a separate lint step. **spotbugs** runs in `verify`.
Config: `checkstyle.xml`, `pmd-ruleset.xml`, `spotbugs-exclude.xml`, `.editorconfig`.
CI (`.github/workflows/ci.yml`) runs `./mvnw verify` on push/PR to `main` and `develop`.

**Before committing: run `make format` then `make lint`** or the build fails.

## Configuration (env vars)

Defaults are for local dev; override in prod.

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `yac` / `yac` / `yac` | PostgreSQL |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `REMEMBER_ME_KEY` | **required — no default** | Startup fails if unset/blank (`SecurityProperties`, `@NotBlank`) |
| `WEBSOCKET_ALLOWED_ORIGINS` | `http://localhost:8080` | Comma-separated CORS origins for WebSocket |
| `FILE_STORAGE_PATH` | `./file-storage` | Upload directory |

Config: `application.yml` (env-var driven), `application-dev.yml` (dev overrides).
Externalize env-specific values via `@ConfigurationProperties`; validate at startup, fail fast.

## Project Structure

```
src/main/java/com/bovae/yac/
├── YacApplication.java        # @SpringBootApplication, @EnableScheduling
├── config/                    # Security, WebSocket, Session, WebMvc + properties/ (@ConfigurationProperties)
├── controller/
│   ├── api/                   # REST → ResponseEntity<T>, mapped /api/**
│   └── web/                   # Thymeleaf pages → String view names
├── service/                   # Business logic (all logic lives here)
├── model/{entity,dto,enums}/  # JPA entities (UUID PKs) · request/response records · enums
├── repository/                # Spring Data JPA (JpaRepository<Entity, UUID>)
├── exception/                 # Domain exceptions + @ControllerAdvice handlers
├── validation/                # Custom Jakarta validators (MaxByteSize, UUID)
└── ws/                        # STOMP handlers (chat, presence, typing)

src/main/resources/
├── application.yml, application-dev.yml
├── db/changelog/              # Liquibase master + NNN-*.sql migrations
├── templates/                 # layout/ fragments/ auth/ chat/ rooms/ profile/ error/
└── static/{css,js}/

src/test/java/com/bovae/yac/
├── unit/          # Mockito, no Spring context
├── parameterized/ # @ParameterizedTest
├── integration/   # @SpringBootTest + MockMvc + Testcontainers
├── model/         # entity/dto tests
└── config/        # TestcontainersConfig (shared PG17 + Redis7)
```

## Conventions

### Controllers
- **Zero business logic** — delegate to services immediately.
- `api/`: `@RestController @RequestMapping("/api/...") @Validated @RequiredArgsConstructor`, return `ResponseEntity<T>`.
- Get user via `Principal`, resolve with `userRepository.findByEmail(principal.getName())`.
- `@Valid` on `@RequestBody`. Status codes: 201 created, 204 no-content, 200 ok.

### Services
- `@Service @RequiredArgsConstructor @Slf4j`. `@Transactional` on writes, `@Transactional(readOnly = true)` on reads.
- Throw domain exceptions; the advice maps them to HTTP. Use `"...%s".formatted(x)`, never string concatenation.

### DTOs
- Prefer `record` types. Requests carry Jakarta validation (`@NotBlank`, `@NotNull`).
- JSON is **snake_case** (global Jackson config) — test assertions use `$.room_id`, `$.created_at`.
- Response records: `@JsonInclude(NON_NULL)`.

### Entities & DB
- UUID PKs (`GenerationType.UUID`), `Instant` timestamps (`@CreationTimestamp`/`@UpdateTimestamp`).
- Lombok `@Data @NoArgsConstructor @AllArgsConstructor @Builder`.
- **All schema changes via Liquibase** — new file `db/changelog/NNN-description.sql`, register in `db.changelog-master.yaml`. Never Hibernate auto-DDL.
- Repositories return `Optional<T>` for single lookups; derived queries preferred, `@Query` JPQL for complex, avoid native SQL.
- Room deletion cascades manually in `RoomService.deleteRoomCascade()` (order: unread markers → invitations → bans → members → attachments → messages → room), **not** JPA cascade annotations.

### Exceptions → HTTP
Mapped in `GlobalApiExceptionHandler` (`/api/**`) and `GlobalWebExceptionHandler` (web). Services throw, advice translates. Response: `{ timestamp, status, message, path }`.

| Exception | Status |
|---|---|
| `ResourceNotFoundException` | 404 |
| `ForbiddenException` | 403 |
| `ConflictException` | 409 |
| `FileStorageException` | 400 |
| `MethodArgumentNotValidException`, `ConstraintViolationException` | 400 |

### WebSocket / STOMP
Endpoint `/ws` (CSRF-exempt), app prefix `/app`, broker `/topic` + `/queue`.
Destinations: `/topic/chat`, `/topic/presence`, `/topic/typing`, `/user/{userId}/queue/notifications`.
Handlers in `ws/`: `/app/chat.{send,edit,delete}`, `/app/presence.heartbeat`, `/app/typing.{start,stop}`.

### Testing
- Integration tests use **real** Postgres 17 + Redis 7 via Testcontainers — no H2/embedded.
  `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfig.class) @Transactional` (rolls back per test).
- MockMvc auth: `.with(user(email).roles("USER"))` + `.with(csrf())` on mutating requests. Assert status + `jsonPath()`.
- Set up test data via service calls in `@BeforeEach`, not raw SQL.
- Unit: `@ExtendWith(MockitoExtension.class)`, `@Mock`/`@InjectMocks`, cover happy + edge + error paths.
- Naming: `methodUnderTest_shouldBehavior_whenCondition`. Parametrize (2+ similar cases) instead of duplicating.

## Domain Rules (gotchas)

- Room names are globally unique; only owners delete rooms, owners+admins moderate.
- Messages: max 3072 UTF-8 bytes; ordered by monotonic per-room **watermarks**; cursor pagination shape `{ messages, has_more, next_cursor }`.
- Files: max 20MB (3MB images); attachments cascade-delete with parent message.
- Friendship is bidirectional (either party removes). User bans block both directions (DMs + friend requests).
- Presence from Redis TTL: heartbeat within 30s = ONLINE, expired = OFFLINE.
- Composite keys: `RoomMember` (room+user), `UnreadMarker` (user+room).

## Deployment: SINGLE-NODE ONLY

Do **not** run more than one instance. The in-memory STOMP simple broker isn't shared across
instances, and presence transition-detection (`lastKnownStatus`) + sweep are per-JVM
(would double-broadcast and miss cross-node sessions). Postgres + Redis are shared/safe.
Horizontal scaling needs an external STOMP relay (RabbitMQ/ActiveMQ via `enableStompBrokerRelay`).
