package com.bovae.yac.controller.web;

import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class ProfileWebController {

    private final UserRepository userRepository;
    private final AuthService authService;

    @GetMapping("/profile")
    public String profile(Model model, Principal principal) {
        User user = userRepository.findByEmail(principal.getName())
                .orElseThrow();
        model.addAttribute("user", user);
        return "profile/index";
    }

    @GetMapping("/profile/sessions")
    public String sessions(Model model, Principal principal) {
        List<AuthService.SessionInfo> sessions = authService.listSessions(principal.getName());
        model.addAttribute("sessions", sessions);
        return "profile/sessions";
    }
}
