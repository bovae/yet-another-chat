package com.bovae.yac.integration;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for input validation and error handling.
 * Verifies that invalid requests are rejected with correct HTTP status codes
 * and that the GlobalApiExceptionHandler maps exceptions to proper responses.
 *
 * Validates Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8, 13.9, 13.10, 13.11
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class ValidationErrorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private com.bovae.yac.repository.UserRepository userRepository;

    private User userA;
    private Room room;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("val-alice@test.com", "valalice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        room = roomService.getRoomById(roomService
                .createRoom("val-test-room", "Validation test room", RoomVisibility.PUBLIC, userA)
                .id());
    }

    // ---- Req 13.1: Blank message content returns 400 ----

    @Test
    void sendMessage_blankContent_returns400() throws Exception {
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": ""}
                                """.formatted(room.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    @Test
    void sendMessage_nullContent_returns400() throws Exception {
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s"}
                                """.formatted(room.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    // ---- Req 13.2: Message exceeding 3072 UTF-8 bytes returns 400 ----

    @Test
    void sendMessage_exceeding3072Bytes_returns400() throws Exception {
        String oversizedContent = "a".repeat(3073);
        mockMvc.perform(post("/api/rooms/{roomId}/messages", room.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"room_id": "%s", "content": "%s"}
                                """.formatted(room.getId(), oversizedContent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    // ---- Req 13.3: Blank room name returns 400 ----

    @Test
    void createRoom_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "", "description": "desc", "visibility": "PUBLIC"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    // ---- Req 13.4: Room name exceeding 100 characters returns 400 ----

    @Test
    void createRoom_nameExceeding100Chars_returns400() throws Exception {
        String longName = "x".repeat(101);
        mockMvc.perform(post("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "description": "desc", "visibility": "PUBLIC"}
                                """.formatted(longName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    // ---- Req 13.5: Invalid email format on password reset returns 400 ----

    @Test
    void passwordResetRequest_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/password/reset-request")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    // ---- Req 13.6: Non-existent room returns 404 ----

    @Test
    void getMessages_nonExistentRoom_returns404() throws Exception {
        UUID fakeRoomId = UUID.randomUUID();
        mockMvc.perform(get("/api/rooms/{roomId}/messages", fakeRoomId)
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    // ---- Req 13.7: Non-existent message returns 404 ----

    @Test
    void deleteMessage_nonExistent_returns404() throws Exception {
        UUID fakeMessageId = UUID.randomUUID();
        mockMvc.perform(delete("/api/rooms/{roomId}/messages/{id}", room.getId(), fakeMessageId)
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    // ---- Req 13.8: Non-existent friendship returns 404 ----

    @Test
    void acceptFriendRequest_nonExistent_returns404() throws Exception {
        UUID fakeFriendshipId = UUID.randomUUID();
        mockMvc.perform(post("/api/friends/{id}/accept", fakeFriendshipId)
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    // ---- Req 13.9: ConflictException maps to 409 ----

    @Test
    void createRoom_duplicateName_returns409() throws Exception {
        // Room "val-test-room" already exists from setUp
        mockMvc.perform(post("/api/rooms")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "val-test-room", "description": "duplicate", "visibility": "PUBLIC"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
    }

    // ---- Req 13.10: FileStorageException maps to 400 ----

    @Test
    void uploadFile_oversizedImage_returns400() throws Exception {
        // Send a message first so we have a valid messageId for the attachment upload
        Message message = messageService.sendMessage(room, userA, "attachment test", null);

        // Real PNG magic bytes so content sniffing classifies it as an image regardless of the
        // declared type; size then exceeds the 3 MB image cap (R1-34).
        byte[] data = new byte[3 * 1024 * 1024 + 1];
        byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(pngMagic, 0, data, 0, pngMagic.length);
        MockMultipartFile oversizedImage = new MockMultipartFile("file", "large-image.png", "image/png", data);

        mockMvc.perform(multipart("/api/rooms/{roomId}/attachments", room.getId())
                        .file(oversizedImage)
                        .param("messageId", message.getId().toString())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
    }

    // ---- Req 13.11: Malformed UUID path variables return 400 ----

    @Test
    void getMessages_malformedUuid_returns400() throws Exception {
        mockMvc.perform(get("/api/rooms/{roomId}/messages", "not-a-uuid")
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRoom_malformedUuid_returns400() throws Exception {
        mockMvc.perform(delete("/api/rooms/{id}", "invalid-uuid")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
