package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByRoomAndWatermarkGreaterThanOrderByWatermarkAsc(Room room, Long watermark, Pageable pageable);

    List<Message> findByRoom(Room room);

    @Modifying
    @Query("UPDATE Message m SET m.replyTo = null WHERE m.replyTo IN (SELECT msg FROM Message msg WHERE msg.room = :room)")
    void nullifyReplyToByRoom(Room room);

    void deleteByRoom(Room room);
}
