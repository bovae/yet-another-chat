package com.bovae.yac.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ChatWebController {

    @GetMapping("/chat")
    public String chat() {
        return "chat/index";
    }
}
