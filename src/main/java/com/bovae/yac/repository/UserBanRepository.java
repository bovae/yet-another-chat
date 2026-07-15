package com.bovae.yac.repository;

import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBanRepository extends JpaRepository<UserBan, UUID> {

    boolean existsByBlockerAndBlocked(User blocker, User blocked);

    List<UserBan> findByBlocker(User blocker);

    List<UserBan> findByBlocked(User blocked);

    Optional<UserBan> findByBlockerAndBlocked(User blocker, User blocked);

    @Query("SELECT ub FROM UserBan ub JOIN FETCH ub.blocked WHERE ub.blocker = :blocker")
    List<UserBan> findByBlockerWithBlocked(@Param("blocker") User blocker);
}
