package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.FriendshipDto;
import com.bovae.yac.model.dto.SendFriendRequest;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.FriendshipService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendshipApiController {

    private final FriendshipService friendshipService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<FriendshipDto>> listFriends(Principal principal) {
        User user = resolveUser(principal);
        List<FriendshipDto> friends = friendshipService.listFriends(user);
        return ResponseEntity.ok(friends);
    }

    @PostMapping("/request")
    public ResponseEntity<Void> sendFriendRequest(@Valid @RequestBody SendFriendRequest request, Principal principal) {
        User requester = resolveUser(principal);
        User recipient = userRepository
                .findByUsername(request.username())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: %s".formatted(request.username())));

        friendshipService.sendFriendRequest(requester, recipient, request.requestText());

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Void> acceptFriendRequest(@PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        friendshipService.acceptFriendRequest(id, user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Void> declineFriendRequest(@PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        friendshipService.declineFriendRequest(id, user);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeFriend(@PathVariable UUID id, Principal principal) {
        User user = resolveUser(principal);
        friendshipService.removeFriend(id, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/requests/incoming")
    public ResponseEntity<List<FriendshipDto>> incomingRequests(Principal principal) {
        User user = resolveUser(principal);
        List<FriendshipDto> incoming = friendshipService.listPendingIncoming(user);
        return ResponseEntity.ok(incoming);
    }

    @GetMapping("/requests/outgoing")
    public ResponseEntity<List<FriendshipDto>> outgoingRequests(Principal principal) {
        User user = resolveUser(principal);
        List<FriendshipDto> outgoing = friendshipService.listPendingOutgoing(user);
        return ResponseEntity.ok(outgoing);
    }

    private User resolveUser(Principal principal) {
        return userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
