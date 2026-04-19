package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preservation property tests for room creation — captures baseline behavior that must
 * remain unchanged after the bugfix is applied.
 *
 * These tests MUST PASS on UNFIXED code. They verify error responses and service layer
 * behavior that are NOT affected by the bug (empty response body on success).
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RoomCreationPreservationPropertyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomMemberRepository roomMemberRepository;

    @Autowired
    private RoomBanRepository roomBanRepository;

    @Autowired
    private RoomInvitationRepository roomInvitationRepository;

    private UserDto testUserDto;

    @AfterTry
    void cleanup() {
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
        testUserDto = null;
    }

    @Provide
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> roomDescriptions() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(0)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<RoomVisibility> roomVisibilities() {
        return Arbitraries.of(RoomVisibility.PUBLIC, RoomVisibility.PRIVATE);
    }

    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.of("", "   ", "      ");
    }

    private User getOrCreateTestUser() {
        if (testUserDto == null) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            testUserDto = userService.register(
                    "preserve-" + suffix + "@example.com",
                    "preserve" + suffix,
                    "password123"
            );
        }
        return userRepository.findById(testUserDto.id()).orElseThrow();
    }

    // Property 2a: Duplicate room name returns 409 with ErrorResponse shape
    /**
     * Validates: Requirements 3.1
     *
     * For all room names that already exist in the database, POST /api/rooms returns 409
     * with ErrorResponse shape (timestamp non-null, status = 409, message non-null,
     * path = "/api/rooms").
     *
     * This behavior is NOT affected by the bug and must be preserved after the fix.
     */
    @Property(tries = 10)
    void duplicateRoomNameReturns409WithErrorResponseShape(
            @ForAll("roomNames") String name,
            @ForAll("roomDescriptions") String description,
            @ForAll("roomVisibilities") RoomVisibility visibility
    ) throws Exception {
        User creator = getOrCreateTestUser();
        String uniqueName = name + "-" + UUID.randomUUID().toString().substring(0, 8);

        // First, create a room so the name exists
        roomService.createRoom(uniqueName, description, visibility, creator);

        // Now attempt to create a room with the same name via the API
        String requestBody = """
                {"name": "%s", "description": "%s", "visibility": "%s"}
                """.formatted(uniqueName, description, visibility.name());

        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .with(user(creator.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).isNotEmpty();

        // Verify ErrorResponse shape: timestamp, status, message, path
        String timestamp = com.jayway.jsonpath.JsonPath.read(responseBody, "$.timestamp");
        assertThat(timestamp)
                .as("ErrorResponse should contain non-null 'timestamp'")
                .isNotNull();

        Integer statusCode = com.jayway.jsonpath.JsonPath.read(responseBody, "$.status");
        assertThat(statusCode)
                .as("ErrorResponse 'status' should be 409")
                .isEqualTo(409);

        String message = com.jayway.jsonpath.JsonPath.read(responseBody, "$.message");
        assertThat(message)
                .as("ErrorResponse should contain non-null 'message'")
                .isNotNull()
                .isNotEmpty();

        String path = com.jayway.jsonpath.JsonPath.read(responseBody, "$.path");
        assertThat(path)
                .as("ErrorResponse 'path' should be '/api/rooms'")
                .isEqualTo("/api/rooms");
    }

    // Property 2b: Blank/empty name returns 400 with validation error
    /**
     * Validates: Requirements 3.1
     *
     * For all blank/empty name strings, POST /api/rooms returns 400 with a validation
     * error response. This behavior is NOT affected by the bug and must be preserved.
     */
    @Property(tries = 5)
    void blankNameReturns400WithValidationError(
            @ForAll("blankStrings") String blankName,
            @ForAll("roomVisibilities") RoomVisibility visibility
    ) throws Exception {
        User creator = getOrCreateTestUser();

        String requestBody = """
                {"name": "%s", "description": "some desc", "visibility": "%s"}
                """.formatted(blankName, visibility.name());

        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .with(user(creator.getEmail()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).isNotEmpty();

        // Verify the response contains error information
        Integer statusCode = com.jayway.jsonpath.JsonPath.read(responseBody, "$.status");
        assertThat(statusCode)
                .as("Validation error 'status' should be 400")
                .isEqualTo(400);

        String message = com.jayway.jsonpath.JsonPath.read(responseBody, "$.message");
        assertThat(message)
                .as("Validation error should contain non-null 'message'")
                .isNotNull()
                .isNotEmpty();
    }

    // Property 2c: Service layer createRoom continues to return saved RoomDto
    /**
     * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
     *
     * For all valid room creation requests, RoomService.createRoom continues to return
     * the saved RoomDto with correct id, name, description, and visibility.
     * This service layer behavior must be preserved after the fix.
     */
    @Property(tries = 10)
    void serviceLayerCreateRoomReturnsSavedEntity(
            @ForAll("roomNames") String name,
            @ForAll("roomDescriptions") String description,
            @ForAll("roomVisibilities") RoomVisibility visibility
    ) {
        User creator = getOrCreateTestUser();
        String uniqueName = name + "-" + UUID.randomUUID().toString().substring(0, 8);

        RoomDto roomDto = roomService.createRoom(uniqueName, description, visibility, creator);

        // RoomDto should be returned (not null)
        assertThat(roomDto)
                .as("RoomService.createRoom should return the saved RoomDto")
                .isNotNull();

        // RoomDto should have a persisted UUID id
        assertThat(roomDto.id())
                .as("Returned RoomDto should have a non-null UUID id")
                .isNotNull();

        // RoomDto fields should match the request
        assertThat(roomDto.name())
                .as("Returned RoomDto name should match the request")
                .isEqualTo(uniqueName);

        assertThat(roomDto.description())
                .as("Returned RoomDto description should match the request")
                .isEqualTo(description);

        assertThat(roomDto.visibility())
                .as("Returned RoomDto visibility should match the request")
                .isEqualTo(visibility);

        // Room should be persisted in the database
        assertThat(roomRepository.findById(roomDto.id()))
                .as("Room should exist in the database")
                .isPresent();
    }
}
