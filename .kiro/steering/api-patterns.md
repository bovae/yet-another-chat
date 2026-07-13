---
inclusion: fileMatch
fileMatchPattern: "src/main/java/com/bovae/yac/controller/**,src/main/java/com/bovae/yac/service/**,src/main/java/com/bovae/yac/model/dto/**"
---

# API & Service Layer Patterns

## Controller Structure

Two controller types, separated by package:

- `controller/web/` — Thymeleaf page controllers. Return `String` view names. Handle page rendering only.
- `controller/api/` — REST API controllers. Return `ResponseEntity<T>`. Mapped under `/api/**`.

Every API controller follows this template:

```java
@Validated
@RestController
@RequestMapping("/api/things")
@RequiredArgsConstructor
public class ThingApiController {
    private final ThingService thingService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody CreateThingRequest request, Principal principal) {
        User user = resolveUser(principal);
        thingService.create(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
```

Key rules:
- Controllers delegate to services immediately — zero business logic in controllers.
- Use `Principal` to get the authenticated user, resolve via `UserRepository.findByEmail()`.
- Include `@Valid` on `@RequestBody` parameters.
- Return appropriate status codes: 201 (created), 204 (no content), 200 (ok).

## Service Layer

- Annotate with `@Service`, `@RequiredArgsConstructor`, `@Slf4j`.
- Use `@Transactional` on methods that write data. Use `@Transactional(readOnly = true)` for reads.
- Throw domain exceptions (`ResourceNotFoundException`, `ForbiddenException`, `ConflictException`) — the `@RestControllerAdvice` maps them to HTTP responses.
- Use `String.formatted()` for parameterized messages, never concatenation.

## DTOs

- Prefer Java `record` types for request/response DTOs.
- Use `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` for snake_case JSON (global Jackson config also applies).
- Request records: add Jakarta validation annotations (`@NotBlank`, `@NotNull`, `@Valid`).
- Response records: add `@JsonInclude(JsonInclude.Include.NON_NULL)` to omit nulls.

```java
public record CreateRoomRequest(
    @NotBlank String name,
    String description,
    @NotNull RoomVisibility visibility
) {}
```

## Exception Handling

All exception-to-HTTP mapping lives in `GlobalApiExceptionHandler` (for `/api/**`) and `GlobalWebExceptionHandler` (for web views). Services throw, the advice translates.

| Exception | HTTP Status |
|---|---|
| `ResourceNotFoundException` | 404 |
| `ForbiddenException` | 403 |
| `ConflictException` | 409 |
| `FileStorageException` | 400 |
| `MethodArgumentNotValidException` | 400 |
| `ConstraintViolationException` | 400 |

Error response shape: `{ timestamp, status, message, path }`

## Authentication in Requests

Spring Security session-based auth. All `/api/**` endpoints require authentication. CSRF token required on all mutating requests (POST, PUT, DELETE). WebSocket endpoint `/ws/**` is CSRF-exempt.
