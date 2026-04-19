package com.bovae.yac.repository;

import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    @Query("SELECT a FROM Attachment a WHERE a.message.room = :room")
    List<Attachment> findByRoom(Room room);
}
