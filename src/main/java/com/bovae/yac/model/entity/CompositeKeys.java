package com.bovae.yac.model.entity;

import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Equality for (room, user) composite-key entities. Two instances are equal only when both id
 * components are non-null and match — transient instances are never equal to anything but
 * themselves.
 */
@UtilityClass
class CompositeKeys {

    static boolean roomUserEquals(
            @Nullable Room room, @Nullable User user, @Nullable Room otherRoom, @Nullable User otherUser) {
        UUID roomId = room == null ? null : room.getId();
        UUID userId = user == null ? null : user.getId();
        UUID otherRoomId = otherRoom == null ? null : otherRoom.getId();
        UUID otherUserId = otherUser == null ? null : otherUser.getId();
        return roomId != null && userId != null && roomId.equals(otherRoomId) && userId.equals(otherUserId);
    }
}
