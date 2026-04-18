package com.bovae.yac.repository;

import com.bovae.yac.model.entity.RoomBan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RoomBanRepository extends JpaRepository<RoomBan, UUID> {
}
