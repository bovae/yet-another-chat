package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.UnreadMarkerId;
import com.bovae.yac.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UnreadMarkerRepository extends JpaRepository<UnreadMarker, UnreadMarkerId> {

    List<UnreadMarker> findByUser(User user);

    Optional<UnreadMarker> findByUserAndRoom(User user, Room room);

    List<UnreadMarker> findByRoom(Room room);
}
