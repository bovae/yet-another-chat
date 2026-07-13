package com.bovae.yac.service;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.UserBanDto;
import com.bovae.yac.model.dto.UserBanMapper;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.entity.UserBan;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.UserBanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserBanService {

    private final UserBanRepository userBanRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserBanMapper userBanMapper;

    @Transactional
    public UserBan banUser(User blocker, User blocked) {
        if (blocker.getId().equals(blocked.getId())) {
            throw new ConflictException("Cannot ban yourself");
        }

        if (userBanRepository.existsByBlockerAndBlocked(blocker, blocked)) {
            throw new ConflictException("User is already banned");
        }

        UserBan userBan = UserBan.builder()
                .blocker(blocker)
                .blocked(blocked)
                .build();

        userBan = userBanRepository.save(userBan);

        deleteFriendshipIfExists(blocker, blocked);

        LOG.info("User banned: blockerId={}, blockedId={}", blocker.getId(), blocked.getId());

        return userBan;
    }

    @Transactional
    public void unbanUser(User blocker, User blocked) {
        UserBan userBan = userBanRepository.findByBlockerAndBlocked(blocker, blocked)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ban found for blockerId=%s, blockedId=%s".formatted(blocker.getId(), blocked.getId())));

        userBanRepository.delete(userBan);

        LOG.info("User unbanned: blockerId={}, blockedId={}", blocker.getId(), blocked.getId());
    }

    public boolean isBanExistsBetween(User userA, User userB) {
        return userBanRepository.existsByBlockerAndBlocked(userA, userB)
                || userBanRepository.existsByBlockerAndBlocked(userB, userA);
    }

    public boolean isBannedBy(User blocked, User blocker) {
        return userBanRepository.existsByBlockerAndBlocked(blocker, blocked);
    }

    public List<UserBanDto> listBannedUsers(User blocker) {
        return userBanMapper.toDtoList(userBanRepository.findByBlockerWithBlocked(blocker));
    }

    private void deleteFriendshipIfExists(User userA, User userB) {
        Optional<Friendship> forward = friendshipRepository.findByRequesterAndRecipient(userA, userB);
        forward.ifPresent(friendshipRepository::delete);

        Optional<Friendship> reverse = friendshipRepository.findByRequesterAndRecipient(userB, userA);
        reverse.ifPresent(friendshipRepository::delete);
    }
}
