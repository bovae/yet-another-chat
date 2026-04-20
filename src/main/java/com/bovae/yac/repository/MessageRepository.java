package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m JOIN FETCH m.sender WHERE m.id = :id")
    Optional<Message> findByIdWithSender(@Param("id") UUID id);

    List<Message> findByRoomAndWatermarkGreaterThanOrderByWatermarkAsc(Room room, Long watermark, Pageable pageable);

    @Query("SELECT m FROM Message m JOIN FETCH m.sender LEFT JOIN FETCH m.replyTo rt LEFT JOIN FETCH rt.sender "
            + "WHERE m.room = :room AND m.watermark > :watermark ORDER BY m.watermark ASC")
    List<Message> findByRoomAndWatermarkGreaterThanWithFetches(
            @Param("room") Room room, @Param("watermark") Long watermark, Pageable pageable);

    List<Message> findByRoom(Room room);

    @Modifying
    @Query("UPDATE Message m SET m.replyTo = null WHERE m.replyTo IN (SELECT msg FROM Message msg WHERE msg.room = :room)")
    void nullifyReplyToByRoom(Room room);

    void deleteByRoom(Room room);
}
