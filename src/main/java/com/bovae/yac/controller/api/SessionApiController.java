package com.bovae.yac.controller.api;

import com.bovae.yac.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionApiController {

    private final AuthService authService;

    @GetMapping
    public ResponseEntity<List<AuthService.SessionInfo>> listSessions(Principal principal) {
        List<AuthService.SessionInfo> sessions = authService.listSessions(principal.getName());
        return ResponseEntity.ok(sessions);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> terminateSession(
            @PathVariable String id,
            Principal principal) {
        // Ensure user is authenticated (principal is non-null via Spring Security)
        authService.terminateSession(id);
        return ResponseEntity.noContent().build();
    }
}
