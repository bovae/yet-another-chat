package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.sender WHERE m.id = :id")
    Optional<Message> findByIdWithSender(@Param("id") UUID id);

    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.sender LEFT JOIN FETCH m.replyTo rt LEFT JOIN FETCH rt.sender "
            + "WHERE m.id = :id")
    Optional<Message> findByIdWithSenderAndReplyTo(@Param("id") UUID id);

    // Ascending catch-up (reconnect): messages newer than a cursor, oldest first.
    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.sender LEFT JOIN FETCH m.replyTo rt LEFT JOIN FETCH rt.sender "
            + "WHERE m.room = :room AND m.watermark > :watermark ORDER BY m.watermark ASC")
    List<Message> findByRoomAndWatermarkGreaterThanWithFetches(
            @Param("room") Room room, @Param("watermark") Long watermark, Pageable pageable);

    // Descending backward pagination: messages older than a cursor, newest first.
    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.sender LEFT JOIN FETCH m.replyTo rt LEFT JOIN FETCH rt.sender "
            + "WHERE m.room = :room AND m.watermark < :before ORDER BY m.watermark DESC")
    List<Message> findByRoomAndWatermarkLessThanWithFetches(
            @Param("room") Room room, @Param("before") Long before, Pageable pageable);

    List<Message> findByRoom(Room room);

    // Unread counts derive from actual undeleted rows so deletions don't inflate them (R1-57).
    long countByRoomAndWatermarkGreaterThan(Room room, Long watermark);

    @Query("SELECT m.watermark FROM Message m WHERE m.room = :room AND m.watermark > :watermark "
            + "ORDER BY m.watermark ASC")
    List<Long> findWatermarksByRoomAndWatermarkGreaterThan(
            @Param("room") Room room, @Param("watermark") Long watermark);

    // One grouped query for a user's unread counts across many rooms (R1-46). Rooms with zero
    // unread simply don't appear in the result. Returns rows of [roomId, count].
    @Query("SELECT m.room.id, count(m) FROM Message m, UnreadMarker um "
            + "WHERE um.user.id = :userId AND um.room.id = m.room.id "
            + "AND m.room.id IN :roomIds AND m.watermark > um.lastReadWatermark "
            + "GROUP BY m.room.id")
    List<Object[]> countUnreadPerRoom(@Param("userId") UUID userId, @Param("roomIds") List<UUID> roomIds);

    // One grouped query for the newest message time per room, to sort the sidebar by
    // recency without a per-room lookup (R3-04). Returns rows of [roomId, maxCreatedAt].
    @Query("SELECT m.room.id, MAX(m.createdAt) FROM Message m WHERE m.room.id IN :roomIds GROUP BY m.room.id")
    List<Object[]> findLastMessageInstantByRoomIds(@Param("roomIds") List<UUID> roomIds);

    @Modifying
    @Query("UPDATE Message m SET m.replyTo = null "
            + "WHERE m.replyTo IN (SELECT msg FROM Message msg WHERE msg.room = :room)")
    void nullifyReplyToByRoom(Room room);

    void deleteByRoom(Room room);
}
