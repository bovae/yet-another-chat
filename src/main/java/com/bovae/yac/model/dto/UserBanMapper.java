package com.bovae.yac.model.dto;

import com.bovae.yac.model.entity.UserBan;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface UserBanMapper {

    @Mapping(source = "blocked.id", target = "blockedId")
    @Mapping(source = "blocked.username", target = "blockedUsername")
    @Mapping(source = "blocked.displayName", target = "blockedDisplayName")
    UserBanDto toDto(UserBan userBan);

    List<UserBanDto> toDtoList(List<UserBan> userBans);
}
