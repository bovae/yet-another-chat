package com.bovae.yac.controller.web;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.RoomCatalogEntry;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomWebController {

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;

    @GetMapping("/catalog")
    public String catalog(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal,
            Model model) {
        Page<RoomCatalogEntry> catalog = roomService.searchCatalog(search, PageRequest.of(page, size));
        model.addAttribute("catalog", catalog);
        model.addAttribute("search", search);

        User user = userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Set<UUID> joinedRoomIds = roomMemberRepository.findRoomIdsByUser(user);
        model.addAttribute("joinedRoomIds", joinedRoomIds);

        return "rooms/catalog";
    }

    @GetMapping("/create")
    public String createForm() {
        return "rooms/create";
    }

    @PostMapping("/{id}/join")
    public String joinRoom(@PathVariable UUID id, Principal principal) {
        User user = userRepository
                .findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Room room = roomService.getRoomById(id);
        roomMemberService.joinPublicRoom(room, user);
        return "redirect:/chat/rooms/" + id;
    }
}
