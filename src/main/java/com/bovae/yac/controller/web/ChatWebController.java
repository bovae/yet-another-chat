package com.bovae.yac.controller.web;

import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.dto.RoomMemberDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.NotificationService;
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
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final RoomBanRepository roomBanRepository;

    @GetMapping("/chat")
    public String chat(Model model, Principal principal) {
        User user = userRepository.findByEmail(principal.getName())
                .orElseThrow();
        model.addAttribute("currentUser", user);
        return "chat/index";
    }

    @GetMapping("/chat/rooms/{id}")
    public String roomView(@PathVariable UUID id, Model model, Principal principal) {
        Room room = roomService.getRoomById(id);
        User user = userRepository.findByEmail(principal.getName())
                .orElseThrow();

        if (roomBanRepository.existsByRoomAndUser(room, user)) {
            throw new ForbiddenException("You are banned from this room");
        }

        boolean isMember = roomMemberService.isMember(room, user);

        if (!isMember && room.getVisibility() != RoomVisibility.PUBLIC) {
            throw new ForbiddenException("Access denied to this room");
        }

        List<RoomMemberDto> members = roomMemberService.listMembers(room);

        RoomRole currentUserRole = members.stream()
                .filter(m -> m.userId().equals(user.getId()))
                .map(RoomMemberDto::role)
                .findFirst()
                .orElse(null);

        if (isMember) {
            notificationService.markRoomAsRead(user, room);
        }

        MessagePage messagePage = messageService.getMessageHistory(room, null, INITIAL_PAGE_SIZE);

        model.addAttribute("room", room);

        if (room.getVisibility() == RoomVisibility.DIRECT && room.getName().startsWith("saved-messages-")) {
            model.addAttribute("displayName", "Saved Messages");
        } else if (room.getVisibility() == RoomVisibility.DIRECT && room.getName().startsWith("dm-")) {
            String dmDisplayName = members.stream()
                    .filter(m -> !m.userId().equals(user.getId()))
                    .findFirst()
                    .map(m -> m.displayName() != null ? m.displayName() : m.username())
                    .map(name -> "Chat with " + name)
                    .orElse(room.getName());
            model.addAttribute("displayName", dmDisplayName);
        } else {
            model.addAttribute("displayName", room.getName());
        }

        model.addAttribute("members", members);
        model.addAttribute("messages", messagePage.messages());
        model.addAttribute("nextCursor", messagePage.nextCursor());
        model.addAttribute("hasMore", messagePage.hasMore());
        model.addAttribute("currentUser", user);
        model.addAttribute("isMember", isMember);
        model.addAttribute("currentUserRole", currentUserRole);

        return "chat/room";
    }
}
