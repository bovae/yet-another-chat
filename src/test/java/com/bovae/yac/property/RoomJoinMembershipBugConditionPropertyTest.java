package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomMember;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomRole;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bug condition exploration property test for room join membership.
 *
 * This test encodes the EXPECTED behavior: when a non-member user clicks "Join"
 * on a public room in the catalog, a POST to /rooms/{id}/join creates a RoomMember
 * entry with MEMBER role, and the subsequent GET /chat/rooms/{id} model contains isMember=true.
 *
 * After the fix:
 * - The catalog "Join" button is a form POST to /rooms/{id}/join
 * - RoomWebController.joinRoom() creates membership and redirects to /chat/rooms/{id}
 * - ChatWebController.roomView() passes isMember=true to the model
 *
 * Validates: Requirements 2.1, 2.2, 2.3
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RoomJoinMembershipBugConditionPropertyTest {

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

    @AfterTry
    void cleanup() {
        roomInvitationRepository.deleteAll();
        roomBanRepository.deleteAll();
        roomMemberRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Provide
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(30)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<String> roomDescriptions() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(80)
        );
    }

    /**
     * Creates a public room owned by a separate owner user.
     * Returns the room entity.
     */
    private Room createPublicRoom(String name, String description) {
        String ownerSuffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto ownerDto = userService.register(
                "owner-" + ownerSuffix + "@test.com",
                "owner" + ownerSuffix,
                "password123"
        );
        User owner = userRepository.findById(ownerDto.id()).orElseThrow();

        Room room = Room.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 8))
                .description(description)
                .visibility(RoomVisibility.PUBLIC)
                .owner(owner)
                .nextWatermark(1L)
                .build();
        room = roomRepository.save(room);

        RoomMember ownerMember = RoomMember.builder()
                .room(room)
                .user(owner)
                .role(RoomRole.OWNER)
                .build();
        roomMemberRepository.save(ownerMember);

        return room;
    }

    /**
     * Creates a non-member user who is NOT a member of any room.
     */
    private User createNonMemberUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto dto = userService.register(
                "joiner-" + suffix + "@test.com",
                "joiner" + suffix,
                "password123"
        );
        return userRepository.findById(dto.id()).orElseThrow();
    }

    // Property 1: Bug Condition — Catalog Join Creates Membership
    /**
     * **Validates: Requirements 2.1, 2.2, 2.3**
     *
     * Bug Condition: input.room.visibility = PUBLIC
     *   AND NOT roomMemberRepository.existsByRoomAndUser(input.room, input.user)
     *   AND input.action = "catalog_join_click"
     *
     * For any non-member user and any public room, simulating the catalog "Join" click
     * (POST /rooms/{id}/join — the fixed form POST behavior) SHALL result in:
     * - A 302 redirect to /chat/rooms/{id}
     * - roomMemberRepository.existsByRoomAndUser(room, user) returns true
     * - Subsequent GET /chat/rooms/{id} model contains "isMember" attribute set to true
     *
     * EXPECTED OUTCOME on fixed code: PASSES (confirms bug is fixed)
     */
    @Property(tries = 5)
    void catalogJoinClickCreatesMembershipAndSetsIsMember(
            @ForAll("roomNames") String roomName,
            @ForAll("roomDescriptions") String description
    ) throws Exception {
        // Setup: create a public room and a non-member user
        Room room = createPublicRoom(roomName, description);
        User nonMember = createNonMemberUser();

        // Precondition: verify user is NOT a member (bug condition holds)
        assertThat(roomMemberRepository.existsByRoomAndUser(room, nonMember))
                .as("Precondition: user should NOT be a member before join action")
                .isFalse();

        // Action: simulate catalog "Join" click — POST to /rooms/{id}/join (fixed flow)
        mockMvc.perform(post("/rooms/{id}/join", room.getId())
                        .with(user(nonMember.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat/rooms/" + room.getId()));

        // Assert: RoomMember entry should exist after the POST join action
        assertThat(roomMemberRepository.existsByRoomAndUser(room, nonMember))
                .as("After catalog join POST, user should be a member of the room")
                .isTrue();

        // Action: GET the chat room view to verify model attributes
        MvcResult result = mockMvc.perform(get("/chat/rooms/{id}", room.getId())
                        .with(user(nonMember.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        // Assert: model should contain isMember=true
        Object isMemberAttr = result.getModelAndView().getModel().get("isMember");
        assertThat(isMemberAttr)
                .as("Model should contain 'isMember' attribute")
                .isNotNull();
        assertThat(isMemberAttr)
                .as("isMember should be true after joining the room via POST")
                .isEqualTo(true);
    }
}
