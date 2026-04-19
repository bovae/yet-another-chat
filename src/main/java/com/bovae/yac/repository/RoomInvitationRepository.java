package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomInvitation;
import com.bovae.yac.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomInvitationRepository extends JpaRepository<RoomInvitation, UUID> {

    Optional<RoomInvitation> findByRoomAndInvitee(Room room, User invitee);

    List<RoomInvitation> findByRoom(Room room);

    List<RoomInvitation> findByInvitee(User invitee);
}
