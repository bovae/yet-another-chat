package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
import com.bovae.yac.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomBanRepository extends JpaRepository<RoomBan, UUID> {

    boolean existsByRoomAndUser(Room room, User user);

    Optional<RoomBan> findByRoomAndUser(Room room, User user);

    List<RoomBan> findByRoom(Room room);
}
