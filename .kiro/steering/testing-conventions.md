---
inclusion: fileMatch
fileMatchPattern: "src/test/**"
---

# Testing Conventions

## Test Infrastructure

All integration tests use Testcontainers with real PostgreSQL 17 and Redis 7 — no H2 or embedded substitutes.

Shared container config: `src/test/java/com/bovae/yac/config/TestcontainersConfig.java`

```java
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class MyIntegrationTest { ... }
```

`@Transactional` on integration test classes ensures each test rolls back, keeping tests isolated.

## Test Organization

```
src/test/java/com/bovae/yac/
├── unit/             # Pure unit tests (Mockito, no Spring context)
├── parameterized/    # @ParameterizedTest with @CsvSource, @MethodSource
├── integration/      # @SpringBootTest + MockMvc + Testcontainers
└── config/           # TestcontainersConfig and shared test utilities
```

- Unit test classes: `*Test.java` (runs with `mvn test`)
- Integration test classes: `*IT.java` or placed in `integration/` package (runs with `mvn verify`)

## Unit Tests

- Use Mockito to mock dependencies. No Spring context needed.
- Test one service method per test. Verify behavior, not implementation.
- Cover happy path, edge cases (null, empty, boundary), and error paths.

```java
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {
    @Mock private RoomRepository roomRepository;
    @InjectMocks private RoomService roomService;

    @Test
    void createRoom_duplicateName_throwsConflict() {
        when(roomRepository.existsByName("taken")).thenReturn(true);
        assertThrows(ConflictException.class,
            () -> roomService.createRoom("taken", "desc", RoomVisibility.PUBLIC, someUser));
    }
}
```

## Integration Tests

- Use MockMvc for REST API testing — no real HTTP server needed.
- Authenticate with `with(user("email").roles("USER"))` and include `with(csrf())` on mutating requests.
- Assert HTTP status codes and JSON response body via `jsonPath()`.
- Set up test data via service calls in `@BeforeEach`, not raw SQL.

```java
mockMvc.perform(post("/api/rooms")
        .with(user(userA.getEmail()).roles("USER"))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"name": "test-room", "description": "desc", "visibility": "PUBLIC"}
            """))
    .andExpect(status().isCreated());
```

## Parameterized Tests

Use JUnit 5 `@ParameterizedTest` with `@CsvSource` or `@MethodSource` for input validation and boundary testing.

```java
@ParameterizedTest
@CsvSource({"'', false", "'a', true", "'x'.repeat(255), true"})
void validateUsername(String input, boolean expected) { ... }
```

## Running Tests

```bash
./mvnw test          # Unit + parameterized tests only
./mvnw verify        # All tests including integration
make test            # Alias for clean verify
```

## Key Conventions

- Logger field is `LOG` (Lombok `@Slf4j`, configured in `lombok.config`)
- JSON uses snake_case (global Jackson config) — test assertions must match: `$.room_id`, `$.created_at`
- Custom exceptions: `ResourceNotFoundException` (404), `ForbiddenException` (403), `ConflictException` (409), `FileStorageException` (400)
- Cursor-based pagination: response shape is `{ messages: [...], has_more: bool, next_cursor: int }`
- All entity IDs are UUIDs
