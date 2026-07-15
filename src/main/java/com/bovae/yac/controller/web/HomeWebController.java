package com.bovae.yac.controller.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

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
