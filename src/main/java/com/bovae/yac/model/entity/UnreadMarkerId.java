package com.bovae.yac.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UnreadMarkerId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID user;
    private UUID room;
}
