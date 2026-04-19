package com.bovae.yac.model.dto;

import com.bovae.yac.model.entity.Room;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface RoomMapper {

    @Mapping(source = "owner.id", target = "ownerId")
    @Mapping(source = "owner.username", target = "ownerUsername")
    RoomDto toDto(Room room);
}
