package com.bovae.yac.model.dto;

import com.bovae.yac.model.entity.Friendship;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface FriendshipMapper {

    @Mapping(source = "requester.id", target = "requesterId")
    @Mapping(source = "requester.username", target = "requesterUsername")
    @Mapping(source = "requester.displayName", target = "requesterDisplayName")
    @Mapping(source = "recipient.id", target = "recipientId")
    @Mapping(source = "recipient.username", target = "recipientUsername")
    @Mapping(source = "recipient.displayName", target = "recipientDisplayName")
    FriendshipDto toDto(Friendship friendship);

    List<FriendshipDto> toDtoList(List<Friendship> friendships);
}
