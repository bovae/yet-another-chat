package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.UserService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserApiController {

    private final UserService userService;
    private final UserRepository userRepository;

    @GetMapping("/search")
    public ResponseEntity<UserSearchResponse> searchByUsername(
            @RequestParam @NotBlank String username,
            Principal principal) {
        resolveUser(principal);

        UserDto found = userService.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: %s".formatted(username)));

        return ResponseEntity.ok(new UserSearchResponse(
                found.id(),
                found.username(),
                found.displayName()
        ));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(Principal principal) {
        User user = resolveUser(principal);
        userService.deleteAccount(user.getId());
        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }

    public record UserSearchResponse(
            UUID id,
            String username,
            String displayName
    ) {}
}
