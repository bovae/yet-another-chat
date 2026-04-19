package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Page<Room> findByVisibilityAndNameContainingIgnoreCase(RoomVisibility visibility, String name, Pageable pageable);

    boolean existsByName(String name);

    List<Room> findByOwner(User owner);

    @Query("SELECT r FROM Room r JOIN FETCH r.owner WHERE r.id = :id")
    Optional<Room> findByIdWithOwner(@Param("id") UUID id);
}
