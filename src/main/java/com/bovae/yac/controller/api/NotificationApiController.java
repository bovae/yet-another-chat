package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.MyRoomEntry;
import com.bovae.yac.model.dto.NotificationSummary;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.FriendshipStatus;
import com.bovae.yac.repository.FriendshipRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationApiController {

    private final RoomService roomService;
    private final FriendshipRepository friendshipRepository;
    private final RoomInvitationRepository roomInvitationRepository;
    private final UserRepository userRepository;

    /** Seeds the navbar aggregate badge on any authenticated page (R3-10). */
    @GetMapping("/summary")
    public ResponseEntity<NotificationSummary> summary(Principal principal) {
        User user = resolveUser(principal);

        int unreadTotal = roomService.listUserRoomsWithUnread(user).stream()
                .mapToInt(MyRoomEntry::unreadCount)
                .sum();
        int pendingFriendRequests =
                (int) friendshipRepository.countByRecipientAndStatus(user, FriendshipStatus.PENDING);
        int pendingInvitations = (int) roomInvitationRepository.countByInvitee(user);

        return ResponseEntity.ok(new NotificationSummary(unreadTotal, pendingFriendRequests, pendingInvitations));
    }

    private User resolveUser(Principal principal) {
        return userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
