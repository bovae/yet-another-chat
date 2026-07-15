package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {

    List<RoomMember> findByRoom(Room room);

    // Owner → Admin → Member, then username; role is stored as STRING so an explicit
    // CASE is needed rather than ordering on the enum value directly (R3-05).
    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user u WHERE rm.room = :room "
            + "ORDER BY CASE rm.role "
            + "WHEN com.bovae.yac.model.enums.RoomRole.OWNER THEN 0 "
            + "WHEN com.bovae.yac.model.enums.RoomRole.ADMIN THEN 1 ELSE 2 END, u.username ASC")
    List<RoomMember> findByRoomWithUsers(@Param("room") Room room);

    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room.id IN :roomIds")
    List<RoomMember> findByRoomIdInWithUsers(@Param("roomIds") List<UUID> roomIds);

    List<RoomMember> findByUser(User user);

    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.room r JOIN FETCH r.owner WHERE rm.user = :user")
    List<RoomMember> findByUserWithRoomAndOwner(@Param("user") User user);

    boolean existsByRoomAndUser(Room room, User user);

    long countByRoom(Room room);

    @Query("SELECT rm.room.id FROM RoomMember rm WHERE rm.user = :user")
    Set<UUID> findRoomIdsByUser(@Param("user") User user);

    @Query("SELECT COUNT(rm) > 0 FROM RoomMember rm WHERE rm.user.id = :userA "
            + "AND rm.room.id IN (SELECT rm2.room.id FROM RoomMember rm2 WHERE rm2.user.id = :userB)")
    boolean existsSharedRoom(@Param("userA") UUID userA, @Param("userB") UUID userB);

    @Query("SELECT DISTINCT rm.user.id FROM RoomMember rm WHERE rm.room.id IN "
            + "(SELECT rm2.room.id FROM RoomMember rm2 WHERE rm2.user.id = :userId) AND rm.user.id <> :userId")
    Set<UUID> findCoMemberUserIds(@Param("userId") UUID userId);
}
