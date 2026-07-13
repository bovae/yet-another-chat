package com.bovae.yac.model.dto;

import com.bovae.yac.model.entity.User;
import org.mapstruct.Mapper;

@Mapper
public interface UserMapper {

    UserDto toDto(User user);
}
