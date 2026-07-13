package com.bovae.yac.service;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService implements UserDetailsService {

    private static final String USER_NOT_FOUND = "User not found with email: '%s'";

    private final UserRepository userRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(USER_NOT_FOUND.formatted(email)));

        LOG.debug("Loaded user for authentication: {}", user.getUsername());

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    /**
     * Returns a list of active session info for the given user email.
     * Each entry contains session ID, creation time, and last accessed time.
     */
    public List<SessionInfo> listSessions(String email) {
        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(email);

        return sessions.entrySet().stream()
                .map(entry -> {
                    Session session = entry.getValue();
                    String ipAddress = extractIpAddress(session);
                    String userAgent = session.getAttribute("USER_AGENT");
                    return new SessionInfo(
                            entry.getKey(),
                            session.getCreationTime(),
                            session.getLastAccessedTime(),
                            userAgent,
                            ipAddress
                    );
                })
                .toList();
    }

    private String extractIpAddress(Session session) {
        String capturedIp = session.getAttribute("CLIENT_IP");
        if (capturedIp != null) {
            return capturedIp;
        }
        SecurityContext securityContext = session.getAttribute("SPRING_SECURITY_CONTEXT");
        if (securityContext == null) {
            return null;
        }
        Authentication authentication = securityContext.getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object details = authentication.getDetails();
        if (details instanceof WebAuthenticationDetails webDetails) {
            return webDetails.getRemoteAddress();
        }
        return null;
    }

    /**
     * Terminates a session, but only if it belongs to the requesting principal (R1-11).
     *
     * @throws ResourceNotFoundException if the session is not one of the caller's own
     */
    public void terminateSession(String sessionId, String principalEmail) {
        Map<String, ? extends Session> ownSessions = sessionRepository.findByPrincipalName(principalEmail);
        if (!ownSessions.containsKey(sessionId)) {
            throw new ResourceNotFoundException("Session not found");
        }
        sessionRepository.deleteById(sessionId);
        LOG.info("Terminated session {} for principal {}", sessionId, principalEmail);
    }

    public record SessionInfo(
            String sessionId,
            Instant creationTime,
            Instant lastAccessedTime,
            String userAgent,
            String ipAddress
    ) {}
}
