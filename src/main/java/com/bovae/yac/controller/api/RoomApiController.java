package com.bovae.yac.controller.api;

import com.bovae.yac.exception.ResourceNotFoundException;
import com.bovae.yac.model.dto.CreateRoomRequest;
import com.bovae.yac.model.dto.RoomCatalogEntry;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomApiController {

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Page<RoomCatalogEntry>> searchCatalog(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal) {
        resolveUser(principal);
        Page<RoomCatalogEntry> catalog = roomService.searchCatalog(search, PageRequest.of(page, size));
        return ResponseEntity.ok(catalog);
    }

    @PostMapping
    public ResponseEntity<RoomDto> createRoom(
            @Valid @RequestBody CreateRoomRequest request,
            Principal principal) {
        User user = resolveUser(principal);
        RoomDto room = roomService.createRoom(request.name(), request.description(), request.visibility(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(room);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoom(
            @PathVariable UUID id,
            Principal principal) {
        User user = resolveUser(principal);
        roomService.deleteRoom(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<Void> joinRoom(
            @PathVariable UUID id,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(id);
        roomMemberService.joinPublicRoom(room, user);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leaveRoom(
            @PathVariable UUID id,
            Principal principal) {
        User user = resolveUser(principal);
        Room room = roomService.getRoomById(id);
        roomMemberService.leaveRoom(room, user);
        return ResponseEntity.noContent().build();
    }

    private User resolveUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found for principal: %s".formatted(principal.getName())));
    }
}
