package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.RoomBan;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preservation property tests for room join membership bugfix.
 *
 * These tests capture the EXISTING correct behavior on UNFIXED code for non-buggy inputs
 * (cases where isBugCondition returns false). They must PASS on unfixed code and continue
 * to pass after the fix is applied, ensuring no regressions.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5
 */
@JqwikSpringSupport
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class RoomJoinMembershipPreservationPropertyTest {
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

    // --- Arbitraries ---

    @Provide
    Arbitrary<String> roomNames() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(30)
                .map(String::toLowerCase);
    }

    @Provide
    Arbitrary<RoomRole> memberRoles() {
        return Arbitraries.of(RoomRole.OWNER, RoomRole.ADMIN, RoomRole.MEMBER);
    }

    @Provide
    Arbitrary<String> searchTerms() {
        return Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20).map(String::toLowerCase)
        );
    }

    // --- Helper methods ---

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto dto = userService.register(
                prefix + "-" + suffix + "@test.com",
                prefix + suffix,
                "password123"
        );
        return userRepository.findById(dto.id()).orElseThrow();
    }

    private Room createPublicRoom(String name) {
        User owner = createUser("owner");
        Room room = Room.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 8))
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
     * Creates a public room and adds a member with the specified role.
     * For OWNER role, returns the room owner directly.
     * For ADMIN/MEMBER roles, creates a separate user and adds them.
     */
    private MemberContext createRoomWithMember(String roomName, RoomRole role) {
        Room room = createPublicRoom(roomName);

        if (role == RoomRole.OWNER) {
            User owner = room.getOwner();
            return new MemberContext(room, owner);
        }

        User member = createUser("member");
        RoomMember roomMember = RoomMember.builder()
                .room(room)
                .user(member)
                .role(role)
                .build();
        roomMemberRepository.save(roomMember);

        return new MemberContext(room, member);
    }

    private record MemberContext(Room room, User user) {}
    // --- Property Tests ---

    /**
     * **Validates: Requirements 3.1, 3.5**
     *
     * Preservation: For all existing members with any role (OWNER, ADMIN, MEMBER),
     * GET /chat/rooms/{id} returns 200 and renders chat/room view with room, members,
     * messages, and currentUser model attributes.
     */
    @Property(tries = 5)
    void existingMemberCanAccessRoomView(
            @ForAll("roomNames") String roomName,
            @ForAll("memberRoles") RoomRole role
    ) throws Exception {
        MemberContext ctx = createRoomWithMember(roomName, role);

        MvcResult result = mockMvc.perform(get("/chat/rooms/{id}", ctx.room().getId())
                        .with(user(ctx.user().getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView()).isNotNull();
        assertThat(result.getModelAndView().getViewName()).isEqualTo("chat/room");

        var model = result.getModelAndView().getModel();
        assertThat(model).containsKey("room");
        assertThat(model).containsKey("members");
        assertThat(model).containsKey("messages");
        assertThat(model).containsKey("currentUser");
    }

    /**
     * **Validates: Requirements 3.2**
     *
     * Preservation: For all non-member + public + non-banned users,
     * POST /api/rooms/{id}/join creates a RoomMember with MEMBER role.
     */
    @Property(tries = 5)
    void apiJoinCreatesRoomMemberForNonBannedNonMember(
            @ForAll("roomNames") String roomName
    ) throws Exception {
        Room room = createPublicRoom(roomName);
        User joiner = createUser("joiner");

        // Precondition: user is not a member and not banned
        assertThat(roomMemberRepository.existsByRoomAndUser(room, joiner)).isFalse();
        assertThat(roomBanRepository.existsByRoomAndUser(room, joiner)).isFalse();

        mockMvc.perform(post("/api/rooms/{id}/join", room.getId())
                        .with(user(joiner.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(roomMemberRepository.existsByRoomAndUser(room, joiner))
                .as("API join should create membership")
                .isTrue();

        RoomMember member = roomMemberRepository.findByRoom(room).stream()
                .filter(rm -> rm.getUser().getId().equals(joiner.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(member.getRole()).isEqualTo(RoomRole.MEMBER);
    }

    /**
     * **Validates: Requirements 3.3**
     *
     * Preservation: For all banned users, POST /api/rooms/{id}/join returns 403.
     */
    @Property(tries = 5)
    void bannedUserCannotJoinViaApi(
            @ForAll("roomNames") String roomName
    ) throws Exception {
        Room room = createPublicRoom(roomName);
        User bannedUser = createUser("banned");

        // Create a ban for this user
        RoomBan ban = RoomBan.builder()
                .room(room)
                .user(bannedUser)
                .bannedBy(room.getOwner())
                .build();
        roomBanRepository.save(ban);

        mockMvc.perform(post("/api/rooms/{id}/join", room.getId())
                        .with(user(bannedUser.getEmail()).roles("USER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(roomMemberRepository.existsByRoomAndUser(room, bannedUser))
                .as("Banned user should not become a member")
                .isFalse();
    }

    /**
     * **Validates: Requirements 3.4**
     *
     * Preservation: For all search terms, GET /rooms/catalog returns 200
     * with catalog and search model attributes.
     */
    @Property(tries = 5)
    void catalogBrowsingReturnsExpectedModelAttributes(
            @ForAll("searchTerms") String searchTerm
    ) throws Exception {
        User viewer = createUser("viewer");

        MvcResult result = mockMvc.perform(get("/rooms/catalog")
                        .param("search", searchTerm)
                        .with(user(viewer.getEmail()).roles("USER")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getModelAndView()).isNotNull();
        assertThat(result.getModelAndView().getViewName()).isEqualTo("rooms/catalog");

        var model = result.getModelAndView().getModel();
        assertThat(model).containsKey("catalog");
        assertThat(model).containsKey("search");
    }
}
