package com.bovae.yac.service;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.FriendshipDto;
import com.bovae.yac.model.dto.FriendshipMapper;
import com.bovae.yac.model.entity.Friendship;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.UserBanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;
    private final UserBanRepository userBanRepository;
    private final FriendshipMapper friendshipMapper;

    @Transactional
    public Friendship sendFriendRequest(User requester, User recipient, String requestText) {
        if (requester.getId().equals(recipient.getId())) {
            throw new ConflictException("Cannot send a friend request to yourself");
        }

        boolean existsForward = friendshipRepository.findByRequesterAndRecipient(requester, recipient).isPresent();
        boolean existsReverse = friendshipRepository.findByRequesterAndRecipient(recipient, requester).isPresent();

        if (existsForward || existsReverse) {
            throw new ConflictException("A friendship already exists between these users");
        }

        if (userBanRepository.existsByBlockerAndBlocked(requester, recipient)
                || userBanRepository.existsByBlockerAndBlocked(recipient, requester)) {
            throw new ForbiddenException("Cannot send friend request — a user ban exists between these users");
        }

        Friendship friendship = Friendship.builder()
                .requester(requester)
                .recipient(recipient)
                .status(FriendshipStatus.PENDING)
                .requestText(requestText)
                .build();

        friendship = friendshipRepository.save(friendship);

        LOG.info("Friend request sent: requesterId={}, recipientId={}", requester.getId(), recipient.getId());

        return friendship;
    }

    @Transactional
    public Friendship acceptFriendRequest(UUID friendshipId, User acceptingUser) {
        Friendship friendship = friendshipRepository.findById(friendshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Friendship not found: %s".formatted(friendshipId)));

        if (!friendship.getRecipient().getId().equals(acceptingUser.getId())) {
            throw new ForbiddenException("Only the recipient can accept a friend request");
        }

        if (friendship.getStatus() != FriendshipStatus.PENDING) {
            throw new ConflictException("Friend request is not in PENDING status");
        }

        friendship.setStatus(FriendshipStatus.ACCEPTED);
        friendship = friendshipRepository.save(friendship);

        LOG.info("Friend request accepted: friendshipId={}, acceptedBy={}", friendshipId, acceptingUser.getId());

        return friendship;
    }

    @Transactional
    public Friendship declineFriendRequest(UUID friendshipId, User decliningUser) {
        Friendship friendship = friendshipRepository.findById(friendshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Friendship not found: %s".formatted(friendshipId)));

        if (!friendship.getRecipient().getId().equals(decliningUser.getId())) {
            throw new ForbiddenException("Only the recipient can decline a friend request");
        }

        if (friendship.getStatus() != FriendshipStatus.PENDING) {
            throw new ConflictException("Friend request is not in PENDING status");
        }

        friendship.setStatus(FriendshipStatus.DECLINED);
        friendship = friendshipRepository.save(friendship);

        LOG.info("Friend request declined: friendshipId={}, declinedBy={}", friendshipId, decliningUser.getId());

        return friendship;
    }

    @Transactional
    public void removeFriend(UUID friendshipId, User user) {
        Friendship friendship = friendshipRepository.findById(friendshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Friendship not found: %s".formatted(friendshipId)));

        boolean isRequester = friendship.getRequester().getId().equals(user.getId());
        boolean isRecipient = friendship.getRecipient().getId().equals(user.getId());

        if (!isRequester && !isRecipient) {
            throw new ForbiddenException("Only participants of the friendship can remove it");
        }

        friendshipRepository.delete(friendship);

        LOG.info("Friendship removed: friendshipId={}, removedBy={}", friendshipId, user.getId());
    }

    public boolean areFriends(User userA, User userB) {
        return friendshipRepository.findByRequesterAndRecipient(userA, userB)
                .filter(f -> f.getStatus() == FriendshipStatus.ACCEPTED)
                .isPresent()
                || friendshipRepository.findByRequesterAndRecipient(userB, userA)
                .filter(f -> f.getStatus() == FriendshipStatus.ACCEPTED)
                .isPresent();
    }

    public List<FriendshipDto> listFriends(User user) {
        List<Friendship> asRequester = friendshipRepository.findByRequesterAndStatusWithUsers(user, FriendshipStatus.ACCEPTED);
        List<Friendship> asRecipient = friendshipRepository.findByRecipientAndStatusWithUsers(user, FriendshipStatus.ACCEPTED);

        List<Friendship> friends = new ArrayList<>(asRequester.size() + asRecipient.size());
        friends.addAll(asRequester);
        friends.addAll(asRecipient);

        return friendshipMapper.toDtoList(friends);
    }

    public List<FriendshipDto> listPendingIncoming(User user) {
        return friendshipMapper.toDtoList(
                friendshipRepository.findByRecipientAndStatusWithUsers(user, FriendshipStatus.PENDING));
    }

    public List<FriendshipDto> listPendingOutgoing(User user) {
        return friendshipMapper.toDtoList(
                friendshipRepository.findByRequesterAndStatusWithUsers(user, FriendshipStatus.PENDING));
    }
}
