package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Page<Room> findByVisibilityAndNameContainingIgnoreCase(RoomVisibility visibility, String name, Pageable pageable);

    boolean existsByName(String name);

    Optional<Room> findByName(String name);

    List<Room> findByOwner(User owner);

    @Query("SELECT r FROM Room r LEFT JOIN FETCH r.owner WHERE r.id = :id")
    Optional<Room> findByIdWithOwner(@Param("id") UUID id);

    /**
     * Atomically reserves the next watermark for a room. The row-level lock the UPDATE
     * takes serializes concurrent senders, so watermarks are gap-free and unique (backed
     * by the {@code (room_id, watermark)} unique constraint). Callers pair this with
     * {@link #nextWatermarkOf(UUID)} within the same transaction to read their reservation.
     */
    @Modifying
    @Query(value = "UPDATE rooms SET next_watermark = next_watermark + 1 WHERE id = :roomId", nativeQuery = true)
    void incrementWatermark(@Param("roomId") UUID roomId);

    @Query(value = "SELECT next_watermark FROM rooms WHERE id = :roomId", nativeQuery = true)
    long nextWatermarkOf(@Param("roomId") UUID roomId);
}
