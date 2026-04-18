package com.bovae.yac.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ProfileWebController {

    @GetMapping("/profile")
    public String profile() {
        return "profile/index";
    }

    @GetMapping("/sessions")
    public String sessions() {
        return "profile/sessions";
    }
}
