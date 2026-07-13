package com.bovae.yac.service;

import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Presence visibility rule (R1-65): a user may see another user's presence only when they are
 * the same user, friends, or share a room. Applied both to STOMP presence SUBSCRIBE and the
 * {@code GET /api/presence} query so the two surfaces stay consistent (design D4).
 */
@Service
@RequiredArgsConstructor
public class PresenceVisibilityService {

    private final FriendshipRepository friendshipRepository;
    private final RoomMemberRepository roomMemberRepository;

    @Transactional(readOnly = true)
    public boolean canView(UUID callerId, UUID targetId) {
        if (callerId.equals(targetId)) {
            return true;
        }
        return friendshipRepository.existsAcceptedBetween(callerId, targetId)
                || roomMemberRepository.existsSharedRoom(callerId, targetId);
    }

    @Transactional(readOnly = true)
    public Set<UUID> visibleAmong(UUID callerId, Collection<UUID> targetIds) {
        Set<UUID> allowed = new HashSet<>(friendshipRepository.findAcceptedFriendIds(callerId));
        allowed.addAll(roomMemberRepository.findCoMemberUserIds(callerId));
        allowed.add(callerId);
        return targetIds.stream().filter(allowed::contains).collect(Collectors.toSet());
    }
}
