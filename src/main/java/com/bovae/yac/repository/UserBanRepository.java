package com.bovae.yac.repository;

import com.bovae.yac.model.entity.UserBan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserBanRepository extends JpaRepository<UserBan, UUID> {
}
