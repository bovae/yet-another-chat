package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomBanRepository extends JpaRepository<RoomBan, UUID> {

    boolean existsByRoomAndUser(Room room, User user);

    Optional<RoomBan> findByRoomAndUser(Room room, User user);

    List<RoomBan> findByRoom(Room room);

    // LEFT JOIN on bannedBy: banned_by_id is nullable (ON DELETE SET NULL, R1-28), so an INNER
    // join would silently drop bans whose issuing admin deleted their account, hiding them from
    // the ban list and making the still-enforced ban impossible to lift (R5-10).
    @Query("SELECT rb FROM RoomBan rb JOIN FETCH rb.user LEFT JOIN FETCH rb.bannedBy WHERE rb.room = :room")
    List<RoomBan> findByRoomWithUserAndBannedBy(@Param("room") Room room);
}
