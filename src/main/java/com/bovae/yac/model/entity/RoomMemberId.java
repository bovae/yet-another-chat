package com.bovae.yac.model.entity;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
// JPA @IdClass contract: Hibernate needs a no-arg constructor and populates the id fields by reflection.
@SuppressWarnings("NullAway.Init")
public class RoomMemberId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID room;
    private UUID user;
}
