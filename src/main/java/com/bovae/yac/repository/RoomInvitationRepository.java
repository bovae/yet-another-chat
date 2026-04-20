package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomInvitationRepository extends JpaRepository<RoomInvitation, UUID> {

    Optional<RoomInvitation> findByRoomAndInvitee(Room room, User invitee);

    List<RoomInvitation> findByRoom(Room room);

    List<RoomInvitation> findByInvitee(User invitee);

    @Query("SELECT ri FROM RoomInvitation ri JOIN FETCH ri.room JOIN FETCH ri.inviter WHERE ri.invitee = :invitee")
    List<RoomInvitation> findByInviteeWithRoomAndInviter(@Param("invitee") User invitee);
}
