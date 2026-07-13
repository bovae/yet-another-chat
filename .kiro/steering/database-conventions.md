---
inclusion: fileMatch
fileMatchPattern: "src/main/resources/db/**,src/main/java/com/bovae/yac/repository/**,src/main/java/com/bovae/yac/model/entity/**"
---

# Database & Entity Conventions

## Migrations

All schema changes go through Liquibase — never rely on Hibernate auto-DDL (`ddl-auto: validate`).

- Master changelog: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Migration files: `src/main/resources/db/changelog/NNN-description.sql`
- Number migrations sequentially: `001-init-schema.sql`, `002-add-xyz.sql`, etc.
- Include the new migration in `db.changelog-master.yaml` with `includeAll` or explicit `include`.

## Entity Conventions

- All entities extend `BaseEntity` or define their own `id` (UUID), `createdAt`, `updatedAt`.
- Use Lombok: `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`.
- Primary keys are `UUID` type, generated with `GenerationType.UUID`.
- Timestamps use `Instant` with `@CreationTimestamp` and `@UpdateTimestamp`.

```java
@Entity
@Table(name = "things")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Thing {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

## Repository Conventions

- Extend `JpaRepository<Entity, UUID>`.
- Use derived query methods where possible (`findByEmail`, `existsByName`).
- Use `@Query` with JPQL for complex queries. Avoid native SQL unless necessary.
- Return `Optional<T>` for single-entity lookups.

## Cascade Deletes

Room deletion cascades manually in `RoomService.deleteRoomCascade()` — not via JPA cascade annotations. This gives explicit control over deletion order (unread markers → invitations → bans → members → attachments → messages → room).

## Key Constraints

- Unique: email, username, room name
- Composite keys: `RoomMember` (room + user), `UnreadMarker` (user + room)
- Unique pairs: friendship (requester + recipient), user ban (blocker + blocked), room ban (room + user), room invitation (room + invitee)
