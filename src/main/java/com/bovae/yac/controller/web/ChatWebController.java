package com.bovae.yac.controller.web;

import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChatWebController {

    private static final int INITIAL_PAGE_SIZE = 50;

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final MessageService messageService;
    private final UserRepository userRepository;

    @GetMapping("/chat")
    public String chat() {
        return "chat/index";
    }

    @GetMapping("/chat/rooms/{id}")
    public String roomView(@PathVariable UUID id, Model model, Principal principal) {
        Room room = roomService.getRoomById(id);
        User user = userRepository.findByEmail(principal.getName())
                .orElseThrow();

        boolean isMember = roomMemberService.isMember(room, user);

        List<RoomMemberDto> members = roomMemberService.listMembers(room);
        MessagePage messagePage = messageService.getMessageHistory(room, null, INITIAL_PAGE_SIZE);

        model.addAttribute("room", room);
        model.addAttribute("members", members);
        model.addAttribute("messages", messagePage.messages());
        model.addAttribute("nextCursor", messagePage.nextCursor());
        model.addAttribute("hasMore", messagePage.hasMore());
        model.addAttribute("currentUser", user);
        model.addAttribute("isMember", isMember);

        return "chat/room";
    }
}
