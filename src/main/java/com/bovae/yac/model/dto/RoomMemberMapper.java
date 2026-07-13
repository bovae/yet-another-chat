package com.bovae.yac.model.dto;

import com.bovae.yac.model.entity.RoomMember;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface RoomMemberMapper {

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.username", target = "username")
    @Mapping(source = "user.displayName", target = "displayName")
    RoomMemberDto toDto(RoomMember roomMember);

    List<RoomMemberDto> toDtoList(List<RoomMember> members);
}
