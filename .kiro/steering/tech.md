# Tech Stack & Tooling

## Runtime

- **Java 21** (Amazon Corretto) — use records, text blocks, pattern matching, sealed classes where appropriate
- **Spring Boot 4.0.5** — modular starters (webmvc, json, thymeleaf, websocket, data-jpa, data-redis, security, actuator, validation, session-data-redis, liquibase)
- **Spring Security** — session-based auth, BCrypt password hashing, form login, remember-me tokens, CSRF protection
- **Spring Session Redis** — `@EnableRedisIndexedHttpSession`, 30-day timeout, multi-device session listing

## Build

- **Maven 3.x** with wrapper (`./mvnw`) — no global Maven install required
- **Lombok 1.18.44** — `@Slf4j` (logger field: `LOG`), `@RequiredArgsConstructor`, `@Data`, `@Builder`
- **lombok.config** — `lombok.log.fieldName = LOG`, copyable annotations: `@Qualifier`, `@Value`
- **spring-boot-maven-plugin** — fat JAR packaging
- **maven-failsafe-plugin** — integration test execution on `mvn verify`

## Database

- **PostgreSQL 17** — primary data store, JDBC driver `org.postgresql:postgresql`
- **Liquibase** — YAML master changelog at `classpath:db/changelog/db.changelog-master.yaml`, SQL migration files
- **JPA/Hibernate** — `ddl-auto: validate` (schema managed by Liquibase, never Hibernate), `open-in-view: false`, UTC timezone
- **Spring Data JPA** — repositories extend `JpaRepository<Entity, UUID>`

## Cache & Session

- **Redis 7** (alpine) — Spring Data Redis for caching, presence tracking (TTL-based), pub/sub notifications
- **Spring Session** — sessions stored in Redis with namespace `spring:session:yac`

## Frontend

- **Thymeleaf** — server-side template engine, layouts + fragments pattern
- **HTMX 2.0.8** — dynamic UI without custom JavaScript, WebSocket extension for real-time updates
- **Bootstrap 5.3.8** — responsive CSS framework
- **STOMP over WebSocket** — `stomp.js 7.0.0` for real-time messaging client
- **WebJars** — all frontend libraries managed via Maven, no Node.js or npm

## Real-Time

- **Spring WebSocket + STOMP** — endpoint `/ws`, application prefix `/app`, broker prefixes `/topic` + `/queue`
- **Message destinations**: `/topic/chat` (room messages), `/topic/presence` (status), `/topic/typing` (indicators)
- **User queues**: `/user/{userId}/queue/notifications` (private notifications)

## Testing

- **JUnit 5** — unit and integration tests
- **Testcontainers 1.21.4** — PostgreSQL 17 + Redis 7 containers, shared via `TestcontainersConfig`
- **MockMvc** — REST API testing without real HTTP server (`spring-boot-starter-webmvc-test`)
- **Spring Security Test** — `@WithMockUser`, `with(user(...))`, `with(csrf())`
- **Mockito** — unit test mocking (bundled with Spring Boot test starter)
- **jqwik 1.9.3** — property-based testing (with `jqwik-spring 0.12.0` integration)

## Deployment

- **Docker** — multi-stage Dockerfile (Corretto 21 build → Corretto 21-alpine runtime), non-root `app` user
- **Docker Compose** — app (port 8080) + PostgreSQL (5432) + Redis (6379), healthchecks, named volumes
- **Actuator** — `/actuator/health` and `/actuator/info` exposed, health details when authorized

## Key Libraries (already in pom.xml)

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-webmvc` | Spring MVC web framework |
| `spring-boot-starter-data-jpa` | JPA + Hibernate |
| `spring-boot-starter-data-redis` | Redis client |
| `spring-boot-starter-security` | Authentication & authorization |
| `spring-boot-starter-validation` | Jakarta Bean Validation |
| `spring-boot-starter-session-data-redis` | Redis-backed HTTP sessions |
| `spring-boot-starter-websocket` | WebSocket + STOMP |
| `spring-boot-starter-thymeleaf` | Template engine |
| `spring-boot-starter-liquibase` | Database migrations |
| `spring-boot-starter-actuator` | Health & metrics endpoints |
| `htmx-spring-boot-thymeleaf` | HTMX integration for Thymeleaf |
| `spring-security-messaging` | WebSocket message security |
| `lombok` | Code generation (provided scope) |
| `postgresql` | JDBC driver (runtime scope) |
| `testcontainers` | Integration test containers (test scope) |
| `jqwik` | Property-based testing (test scope) |
