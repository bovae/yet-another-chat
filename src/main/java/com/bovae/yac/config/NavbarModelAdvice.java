package com.bovae.yac.config;

import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class NavbarModelAdvice {

    private final UserRepository userRepository;

    @ModelAttribute("navbarUser")
    public @Nullable User navbarUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).orElse(null);
    }

    /** Current request path + section, used by the navbar to highlight the active item (R3-09). */
    @ModelAttribute("activePath")
    public String activePath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("activeSection")
    public String activeSection(HttpServletRequest request) {
        return request.getParameter("section");
    }
}
