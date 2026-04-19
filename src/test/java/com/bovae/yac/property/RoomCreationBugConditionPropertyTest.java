package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.RoomBanRepository;
import com.bovae.yac.repository.RoomInvitationRepository;
import com.bovae.yac.repository.RoomMemberRepository;
import com.bovae.yac.repository.RoomRepository;
import com.bovae.yac.repository.UserRepository;
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
 * Bug condition exploration property test for room creation empty response body.
 *
 * This test encodes the EXPECTED behavior: POST /api/rooms should return HTTP 201
 * with a JSON body containing the created room's id, name, description, and visibility.
 *
 * On UNFIXED code, this test is EXPECTED TO FAIL because RoomApiController.createRoom
 * returns ResponseEntity<Void> with no body — the response body assertions will fail.
 *
 * Validates: Requirements 1.1, 1.2, 2.1
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RoomCreationBugConditionPropertyTest {

    @Autowired
    private MockMvc mockMvc;

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

    private UserDto testUser;

    @AfterTry
    void cleanup() {
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
        testUser = null;
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

    private UserDto getOrCreateTestUser() {
        if (testUser == null) {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            testUser = userService.register(
                    "bugtest-" + suffix + "@example.com",
                    "bugtest" + suffix,
                    "password123"
            );
        }
        return testUser;
    }

    // Property 1: Bug Condition — Room Creation Returns Empty Body
    /**
     * Validates: Requirements 1.1, 1.2, 2.1
     *
     * For any valid CreateRoomRequest submitted to POST /api/rooms that results in a
     * successful room creation, the API SHALL return HTTP 201 with a JSON body containing
     * the created room's id (UUID), name (String), description (String), and visibility (enum).
     *
     * Bug Condition: POST /api/rooms returns HTTP 201 with empty body AND client calls
     * resp.json() on empty body — causing a false "Failed to create room" error.
     *
     * EXPECTED OUTCOME on unfixed code: FAILS because RoomApiController.createRoom returns
     * ResponseEntity<Void> with no body.
     */
    @Property(tries = 10)
    void roomCreationReturnsJsonBody(
            @ForAll("roomNames") String name,
            @ForAll("roomDescriptions") String description,
            @ForAll("roomVisibilities") RoomVisibility visibility
    ) throws Exception {
        UserDto creator = getOrCreateTestUser();
        String uniqueName = name + "-" + UUID.randomUUID().toString().substring(0, 8);

        String descriptionJson = description == null
                ? "null"
                : "\"" + description + "\"";

        String requestBody = """
                {"name": "%s", "description": %s, "visibility": "%s"}
                """.formatted(uniqueName, descriptionJson, visibility.name());

        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .with(user(creator.email()).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();

        // Assert response body is NOT empty
        assertThat(responseBody)
                .as("Response body should not be empty — bug condition: empty body causes resp.json() to throw")
                .isNotEmpty();

        // Assert response body contains id field (valid UUID)
        String id = com.jayway.jsonpath.JsonPath.read(responseBody, "$.id");
        assertThat(id)
                .as("Response body should contain 'id' field with a valid UUID")
                .isNotNull();
        assertThat(UUID.fromString(id))
                .as("'id' field should be a valid UUID")
                .isNotNull();

        // Assert response body name matches the request name
        String responseName = com.jayway.jsonpath.JsonPath.read(responseBody, "$.name");
        assertThat(responseName)
                .as("Response body 'name' should match the request name")
                .isEqualTo(uniqueName);

        // Assert response body description matches the request description
        String responseDescription = com.jayway.jsonpath.JsonPath.read(responseBody, "$.description");
        assertThat(responseDescription)
                .as("Response body 'description' should match the request description")
                .isEqualTo(description);

        // Assert response body visibility matches the request visibility
        String responseVisibility = com.jayway.jsonpath.JsonPath.read(responseBody, "$.visibility");
        assertThat(responseVisibility)
                .as("Response body 'visibility' should match the request visibility")
                .isEqualTo(visibility.name());
    }
}
