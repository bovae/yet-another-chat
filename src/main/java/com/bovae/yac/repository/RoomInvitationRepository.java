package com.bovae.yac.repository;

import com.bovae.yac.model.entity.RoomInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RoomInvitationRepository extends JpaRepository<RoomInvitation, UUID> {
}
