package com.bovae.yac.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Captures the User-Agent and client IP into the HTTP session at login so the sessions screen
 * can show where each session was established (R1-48). Preserves the previous redirect-to-/chat
 * behaviour.
 */
@Component
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    public LoginSuccessHandler() {
        setDefaultTargetUrl("/chat");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        HttpSession session = request.getSession();
        session.setAttribute("USER_AGENT", request.getHeader("User-Agent"));
        session.setAttribute("CLIENT_IP", request.getRemoteAddr());
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
