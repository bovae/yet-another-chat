package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.DirectChatService;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behavioural regression tests for two previously-reported bugs. The source-text-asserting
 * exploration tests (bug2–bug5, bug7) were removed (R1-52); only the MockMvc behaviour tests remain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class BugConditionExplorationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private DirectChatService directChatService;

    @Autowired
    private UserRepository userRepository;

    private User userA;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto userADto = userService.register("bugtest-a-" + suffix + "@test.com", "buga" + suffix, "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
    }

    @Test
    @DisplayName("Bug 1: Edit message with content-only body succeeds")
    void bug1_editMessageWithContentOnlyBody_returns200() throws Exception {
        Room room = roomService.getRoomById(
                roomService.createRoom("bug1-room-" + UUID.randomUUID().toString().substring(0, 8),
                        "test", RoomVisibility.PUBLIC, userA).id());

        Message message = messageService.sendMessage(room, userA, "Original content", null);

        mockMvc.perform(put("/api/rooms/{roomId}/messages/{id}", room.getId(), message.getId())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "Updated content"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bug 6: Saved Messages room view displays a friendly name, not raw UUID")
    void bug6_savedMessagesRoom_displaysFriendlyName() throws Exception {
        RoomDto savedRoom = directChatService.getOrCreateSavedMessages(userA);

        Room room = roomService.getRoomById(savedRoom.id());
        assertThat(room.getName()).startsWith("saved-messages-");

        String responseBody = mockMvc.perform(get("/chat/rooms/{id}", savedRoom.id())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody)
                .as("Room view should not display raw saved-messages-UUID name")
                .doesNotContain(room.getName());
    }
}
