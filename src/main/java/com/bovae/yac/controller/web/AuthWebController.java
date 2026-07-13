package com.bovae.yac.controller.web;

import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.service.PasswordService;
import com.bovae.yac.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@Slf4j
public class AuthWebController {

    private final UserService userService;
    private final PasswordService passwordService;

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/register")
    public String register() {
        return "auth/register";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword() {
        return "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        return "auth/reset-password";
    }

    @PostMapping("/register")
    public String registerPost(@RequestParam String email,
                               @RequestParam String username,
                               @RequestParam String password,
                               @RequestParam String confirmPassword,
                               RedirectAttributes redirectAttributes,
                               Model model) {
        if (password.length() < 8) {
            model.addAttribute("error", "Password must be at least 8 characters");
            model.addAttribute("email", email);
            model.addAttribute("username", username);
            return "auth/register";
        }

        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords do not match");
            model.addAttribute("email", email);
            model.addAttribute("username", username);
            return "auth/register";
        }

        try {
            userService.register(email, username, password);
            redirectAttributes.addFlashAttribute("success", "Account created. Please sign in.");
            return "redirect:/login";
        } catch (ConflictException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            model.addAttribute("username", username);
            return "auth/register";
        }
    }

    @PostMapping("/forgot-password")
    public String forgotPasswordPost(@RequestParam String email,
                                     RedirectAttributes redirectAttributes) {
        // Token is created and mailed out-of-band; never rendered in the response (R1-10).
        passwordService.requestReset(email);
        redirectAttributes.addFlashAttribute("success",
                "If an account with that email exists, a password reset link has been sent.");
        return "redirect:/forgot-password";
    }
}
