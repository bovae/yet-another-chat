package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.DirectChatDto;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/direct-chats")
@RequiredArgsConstructor
public class DirectChatApiController {

    private final DirectChatService directChatService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<RoomDto> createOrGetDirectChat(
            @Valid @RequestBody DirectChatRequest request,
            Principal principal) {
        User currentUser = resolveUser(principal);
        User otherUser = resolveUserById(request.userId());

        RoomDto room = directChatService.getOrCreateDirectChat(currentUser, otherUser);

        return ResponseEntity.ok(room);
    }

    @GetMapping
    public ResponseEntity<List<DirectChatDto>> listDirectChats(Principal principal) {
        User user = resolveUser(principal);
        List<DirectChatDto> directChats = directChatService.listDirectChats(user);
        return ResponseEntity.ok(directChats);
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }

    private User resolveUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: %s".formatted(userId)));
    }

    public record DirectChatRequest(
            @NotNull UUID userId
    ) {}
}
