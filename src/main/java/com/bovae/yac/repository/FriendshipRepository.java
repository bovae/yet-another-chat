package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

    List<Friendship> findByRequesterAndStatus(User requester, FriendshipStatus status);

    List<Friendship> findByRecipientAndStatus(User recipient, FriendshipStatus status);

    Optional<Friendship> findByRequesterAndRecipient(User requester, User recipient);

    List<Friendship> findByRequesterOrRecipient(User requester, User recipient);

    @Query("SELECT f FROM Friendship f JOIN FETCH f.requester JOIN FETCH f.recipient WHERE f.requester = :user AND f.status = :status")
    List<Friendship> findByRequesterAndStatusWithUsers(@Param("user") User user, @Param("status") FriendshipStatus status);

    @Query("SELECT f FROM Friendship f JOIN FETCH f.requester JOIN FETCH f.recipient WHERE f.recipient = :user AND f.status = :status")
    List<Friendship> findByRecipientAndStatusWithUsers(@Param("user") User user, @Param("status") FriendshipStatus status);
}
