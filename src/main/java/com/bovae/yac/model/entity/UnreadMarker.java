package com.bovae.yac.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "room"})
@Entity
@Table(name = "unread_markers")
@IdClass(UnreadMarkerId.class)
public class UnreadMarker {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "last_read_watermark")
    private Long lastReadWatermark;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UnreadMarker other)) {
            return false;
        }
        UUID roomId = room == null ? null : room.getId();
        UUID userId = user == null ? null : user.getId();
        UUID otherRoomId = other.room == null ? null : other.room.getId();
        UUID otherUserId = other.user == null ? null : other.user.getId();
        return roomId != null && userId != null
                && roomId.equals(otherRoomId) && userId.equals(otherUserId);
    }

    @Override
    public int hashCode() {
        return UnreadMarker.class.hashCode();
    }
}
