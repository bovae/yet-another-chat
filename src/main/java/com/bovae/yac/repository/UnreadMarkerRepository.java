package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.UnreadMarkerId;
import com.bovae.yac.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnreadMarkerRepository extends JpaRepository<UnreadMarker, UnreadMarkerId> {

    List<UnreadMarker> findByUser(User user);

    Optional<UnreadMarker> findByUserAndRoom(User user, Room room);

    List<UnreadMarker> findByRoom(Room room);

    /**
     * Idempotent read acknowledgement (R1-70): first-read races never 500 and the watermark
     * never regresses. GREATEST ignores a NULL existing value, so a fresh marker just adopts
     * the new watermark.
     */
    @Modifying
    @Query(value = "INSERT INTO unread_markers (user_id, room_id, last_read_watermark) "
            + "VALUES (:userId, :roomId, :watermark) "
            + "ON CONFLICT (user_id, room_id) DO UPDATE "
            + "SET last_read_watermark = GREATEST(unread_markers.last_read_watermark, EXCLUDED.last_read_watermark)",
            nativeQuery = true)
    void upsertLastRead(@Param("userId") UUID userId, @Param("roomId") UUID roomId, @Param("watermark") long watermark);

    /** Creates a marker at join time (R1-24) without disturbing an existing one. */
    @Modifying
    @Query(value = "INSERT INTO unread_markers (user_id, room_id, last_read_watermark) "
            + "VALUES (:userId, :roomId, :watermark) ON CONFLICT (user_id, room_id) DO NOTHING",
            nativeQuery = true)
    void insertMarkerIfAbsent(@Param("userId") UUID userId, @Param("roomId") UUID roomId, @Param("watermark") long watermark);
}
