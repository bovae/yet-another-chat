package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

    List<Friendship> findByRequesterAndStatus(User requester, FriendshipStatus status);

    List<Friendship> findByRecipientAndStatus(User recipient, FriendshipStatus status);

    long countByRecipientAndStatus(User recipient, FriendshipStatus status);

    Optional<Friendship> findByRequesterAndRecipient(User requester, User recipient);

    List<Friendship> findByRequesterOrRecipient(User requester, User recipient);

    @Query("SELECT f FROM Friendship f JOIN FETCH f.requester JOIN FETCH f.recipient WHERE f.requester = :user AND f.status = :status")
    List<Friendship> findByRequesterAndStatusWithUsers(@Param("user") User user, @Param("status") FriendshipStatus status);

    @Query("SELECT f FROM Friendship f JOIN FETCH f.requester JOIN FETCH f.recipient WHERE f.recipient = :user AND f.status = :status")
    List<Friendship> findByRecipientAndStatusWithUsers(@Param("user") User user, @Param("status") FriendshipStatus status);

    @Query("SELECT COUNT(f) > 0 FROM Friendship f WHERE f.status = com.bovae.yac.model.enums.FriendshipStatus.ACCEPTED "
            + "AND ((f.requester.id = :a AND f.recipient.id = :b) OR (f.requester.id = :b AND f.recipient.id = :a))")
    boolean existsAcceptedBetween(@Param("a") UUID a, @Param("b") UUID b);

    @Query("SELECT CASE WHEN f.requester.id = :userId THEN f.recipient.id ELSE f.requester.id END "
            + "FROM Friendship f WHERE f.status = com.bovae.yac.model.enums.FriendshipStatus.ACCEPTED "
            + "AND (f.requester.id = :userId OR f.recipient.id = :userId)")
    Set<UUID> findAcceptedFriendIds(@Param("userId") UUID userId);
}
