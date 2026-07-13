package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for MessageApiController: send, edit, delete messages,
 * and cursor-paginated message history.
 *
 * Validates Requirements: 7.3
 * Validates Correctness Properties: CP 17, CP 18, CP 19, CP 22, CP 23
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class MessageApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserRepository userRepository;

    private User userA;
    private User userB;
    private Room room;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("alice@test.com", "alice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register("bob@test.com", "bob", "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
        room = roomService.getRoomById(roomService.createRoom("test-room", "A test room", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(room, userB);
    }

    // ---- Send message ----

    @Test
    void sendMessage_returns201WithSnakeCaseFields() throws Exception {
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Hello world"}
                                """.formatted(room.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.sender_id", is(userA.getId().toString())))
                .andExpect(jsonPath("$.sender_username", is("alice")))
                .andExpect(jsonPath("$.content", is("Hello world")))
                .andExpect(jsonPath("$.reply_to_id", nullValue()))
                .andExpect(jsonPath("$.edited", is(false)))
                .andExpect(jsonPath("$.watermark", notNullValue()));
    }

    // ---- Edit message ----

    @Test
    void editMessage_returns200WithUpdatedContent() throws Exception {
        Message msg = messageService.sendMessage(room, userA, "Original content", null);

        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "Edited content"}
                                """.formatted(room.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", is("Edited content")))
                .andExpect(jsonPath("$.edited", is(true)))
                .andExpect(jsonPath("$.sender_id", is(userA.getId().toString())));
    }

    // ---- Delete message by author ----

    @Test
    void deleteMessage_byAuthor_returns204() throws Exception {
        Message msg = messageService.sendMessage(room, userA, "To be deleted", null);

        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), msg.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // Verify message no longer appears in history
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(0)));
    }

    // ---- Cursor-paginated message history ----

    @Test
    void getMessages_returnsSnakeCasePaginationFields() throws Exception {
        messageService.sendMessage(room, userA, "Message 1", null);

        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.has_more", is(false)))
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andExpect(jsonPath("$.messages[0].sender_id", is(userA.getId().toString())))
                .andExpect(jsonPath("$.messages[0].created_at", notNullValue()))
                .andExpect(jsonPath("$.messages[0].reply_to_id", nullValue()));
    }

    @Test
    void getMessages_withPagination_returnsHasMoreAndNextCursor() throws Exception {
        // Send 3 messages, request page size of 2
        messageService.sendMessage(room, userA, "Message 1", null);
        messageService.sendMessage(room, userA, "Message 2", null);
        messageService.sendMessage(room, userB, "Message 3", null);

        // Initial page opens on the NEWEST messages, rendered oldest-first (R1-01).
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.has_more", is(true))) // Message 1 is older
                .andExpect(jsonPath("$.next_cursor", notNullValue()))
                .andExpect(jsonPath("$.messages[0].content", is("Message 2")))
                .andExpect(jsonPath("$.messages[1].content", is("Message 3")));
    }

    @Test
    void getMessages_loadOlder_usesBeforeCursor() throws Exception {
        messageService.sendMessage(room, userA, "Message 1", null);
        Message msg2 = messageService.sendMessage(room, userA, "Message 2", null);
        messageService.sendMessage(room, userB, "Message 3", null);

        // "Load older": before = watermark of message 2 → returns only message 1 (R1-02).
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .param("before", msg2.getWatermark().toString())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(1)))
                .andExpect(jsonPath("$.has_more", is(false)))
                .andExpect(jsonPath("$.messages[0].content", is("Message 1")));
    }

    @Test
    void getMessages_emptyRoom_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(0)))
                .andExpect(jsonPath("$.has_more", is(false)))
                .andExpect(jsonPath("$.next_cursor", nullValue()));
    }
}
