package com.bovae.yac.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

@Controller
public class HomeWebController {

    @GetMapping("/")
    public String home(Principal principal) {
        if (principal != null) {
            return "redirect:/chat";
        }
        return "redirect:/login";
    }
}
