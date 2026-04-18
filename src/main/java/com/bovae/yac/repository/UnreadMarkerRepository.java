package com.bovae.yac.repository;

import com.bovae.yac.model.entity.UnreadMarker;
import com.bovae.yac.model.entity.UnreadMarkerId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnreadMarkerRepository extends JpaRepository<UnreadMarker, UnreadMarkerId> {
}
