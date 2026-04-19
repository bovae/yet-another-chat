package com.bovae.yac.integration;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Attachment;
import com.bovae.yac.model.entity.Message;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.AttachmentRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for AttachmentApiController: file upload, download,
 * and non-member access denial.
 *
 * Validates Requirements: 7.9
 * Validates Correctness Properties: CP 21
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class AttachmentApiIT {

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
    private AttachmentRepository attachmentRepository;

    @Autowired
    private com.bovae.yac.repository.UserRepository userRepository;

    private User userA;
    private User userB;
    private User nonMember;
    private Room room;
    private Message message;

    @BeforeEach
    void setUp() {
        UserDto userADto = userService.register("alice@test.com", "alice", "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register("bob@test.com", "bob", "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
        UserDto nonMemberDto = userService.register("carol@test.com", "carol", "testpass123");
        nonMember = userRepository.findById(nonMemberDto.id()).orElseThrow();

        room = roomService.getRoomById(roomService.createRoom("attach-room", "desc", RoomVisibility.PUBLIC, userA).id());
        roomMemberService.joinPublicRoom(room, userB);

        message = messageService.sendMessage(room, userA, "message with attachment", null);
    }

    // ---- File upload ----

    @Test
    void uploadFile_asMember_returns201() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", MediaType.TEXT_PLAIN_VALUE, "hello world".getBytes());

        mockMvc.perform(multipart("/api/rooms/{roomId}/attachments", room.getId())
                        .file(file)
                        .param("messageId", message.getId().toString())
                        .param("comment", "a test file")
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isCreated());

        List<Attachment> attachments = attachmentRepository.findByRoom(room);
        assertThat(attachments).hasSize(1);
        assertThat(attachments.getFirst().getOriginalFileName()).isEqualTo("test.txt");
    }

    @Test
    void uploadFile_byAnotherMember_returns201() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "some notes".getBytes());

        mockMvc.perform(multipart("/api/rooms/{roomId}/attachments", room.getId())
                        .file(file)
                        .param("messageId", message.getId().toString())
                        .with(user(userB.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isCreated());
    }

    // ---- File download ----

    @Test
    void downloadFile_asMember_returns200WithContent() throws Exception {
        // Upload a file first via service
        MockMultipartFile file = new MockMultipartFile(
                "file", "download-me.txt", MediaType.TEXT_PLAIN_VALUE, "file content".getBytes());

        mockMvc.perform(multipart("/api/rooms/{roomId}/attachments", room.getId())
                        .file(file)
                        .param("messageId", message.getId().toString())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isCreated());

        Attachment attachment = attachmentRepository.findByRoom(room).getFirst();

        mockMvc.perform(get("/api/rooms/{roomId}/attachments/{id}/download", room.getId(), attachment.getId())
                        .with(user(userA.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"download-me.txt\""));
    }

    // ---- Non-member access denial ----

    @Test
    void uploadFile_asNonMember_returns403() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "secret.txt", MediaType.TEXT_PLAIN_VALUE, "secret data".getBytes());

        mockMvc.perform(multipart("/api/rooms/{roomId}/attachments", room.getId())
                        .file(file)
                        .param("messageId", message.getId().toString())
                        .with(user(nonMember.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadFile_asNonMember_returns403() throws Exception {
        // Upload a file as a member first
        MockMultipartFile file = new MockMultipartFile(
                "file", "private.txt", MediaType.TEXT_PLAIN_VALUE, "private content".getBytes());

        mockMvc.perform(multipart("/api/rooms/{roomId}/attachments", room.getId())
                        .file(file)
                        .param("messageId", message.getId().toString())
                        .with(user(userA.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isCreated());

        Attachment attachment = attachmentRepository.findByRoom(room).getFirst();

        mockMvc.perform(get("/api/rooms/{roomId}/attachments/{id}/download", room.getId(), attachment.getId())
                        .with(user(nonMember.getEmail()).roles("USER")))
                .andExpect(status().isForbidden());
    }
}
