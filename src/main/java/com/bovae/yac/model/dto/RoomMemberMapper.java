package com.bovae.yac.model.dto;

import com.bovae.yac.model.entity.RoomMember;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface RoomMemberMapper {

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.username", target = "username")
    @Mapping(source = "user.displayName", target = "displayName")
    RoomMemberDto toDto(RoomMember roomMember);

    List<RoomMemberDto> toDtoList(List<RoomMember> members);
}
