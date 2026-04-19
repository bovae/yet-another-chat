package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.RoomMemberId;
import com.bovae.yac.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {

    List<RoomMember> findByRoom(Room room);

    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.user WHERE rm.room = :room")
    List<RoomMember> findByRoomWithUsers(@Param("room") Room room);

    List<RoomMember> findByUser(User user);

    @Query("SELECT rm FROM RoomMember rm JOIN FETCH rm.room r JOIN FETCH r.owner WHERE rm.user = :user")
    List<RoomMember> findByUserWithRoomAndOwner(@Param("user") User user);

    boolean existsByRoomAndUser(Room room, User user);
}
