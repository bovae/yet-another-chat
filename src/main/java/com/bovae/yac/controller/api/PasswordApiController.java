package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.PasswordChangeRequest;
import com.bovae.yac.model.dto.PasswordResetConfirmRequest;
import com.bovae.yac.model.dto.PasswordResetRequest;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PasswordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Optional;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/password")
@RequiredArgsConstructor
public class PasswordApiController {

    private final PasswordService passwordService;
    private final UserRepository userRepository;

    @PostMapping("/reset-request")
    public ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        Optional<User> user = userRepository.findByEmail(request.email());
        // Always return 200 to prevent email enumeration
        user.ifPresent(passwordService::createResetToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/change")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            Principal principal) {
        User user = resolveUser(principal);
        passwordService.changePassword(user.getId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok().build();
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
