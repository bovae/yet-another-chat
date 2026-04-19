package com.bovae.yac.property;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.exception.ConflictException;
import com.bovae.yac.exception.ForbiddenException;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
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
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for RoomService.updateRoom() round-trip.
 *
 * Validates: Requirements 2.2
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfig.class)
class RoomUpdatePropertyTest {

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

    @AfterTry
    void cleanup() {
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
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(0)
                .ofMaxLength(50);
    }

    @Provide
    Arbitrary<RoomVisibility> roomVisibilities() {
        return Arbitraries.of(RoomVisibility.PUBLIC, RoomVisibility.PRIVATE);
    }

    @Provide
    Arbitrary<String> validPasswords() {
        return Arbitraries.strings()
                .ascii()
                .ofMinLength(8)
                .ofMaxLength(30);
    }

    // Feature: ui-completion-and-fixes, Property 1: Room update round-trip preserves fields
    /**
     * Validates: Requirements 2.2
     *
     * For any valid UpdateRoomRequest submitted by the room owner with non-null name/description/visibility,
     * the returned RoomDto SHALL contain the updated values.
     */
    @Property(tries = 100)
    void roomUpdateRoundTripPreservesFields(
            @ForAll("roomNames") String originalName,
            @ForAll("roomDescriptions") String originalDesc,
            @ForAll("roomVisibilities") RoomVisibility originalVis,
            @ForAll("roomNames") String newName,
            @ForAll("roomDescriptions") String newDesc,
            @ForAll("roomVisibilities") RoomVisibility newVis
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "owner" + suffix + "@example.com";
        String username = "owner" + suffix;

        User owner = userRepository.findById(
                userService.register(email, username, "password123").id()
        ).orElseThrow();

        String uniqueOriginalName = originalName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(
                roomService.createRoom(uniqueOriginalName, originalDesc, originalVis, owner).id()
        );

        String uniqueNewName = newName + "-" + UUID.randomUUID();

        RoomDto updated = roomService.updateRoom(room.getId(), owner, uniqueNewName, newDesc, newVis);

        assertThat(updated.name()).isEqualTo(uniqueNewName);
        assertThat(updated.description()).isEqualTo(newDesc);
        assertThat(updated.visibility()).isEqualTo(newVis);

        // Verify persisted entity matches
        Room persisted = roomRepository.findById(room.getId()).orElseThrow();
        assertThat(persisted.getName()).isEqualTo(uniqueNewName);
        assertThat(persisted.getDescription()).isEqualTo(newDesc);
        assertThat(persisted.getVisibility()).isEqualTo(newVis);
    }

    // Feature: ui-completion-and-fixes, Property 1: Non-owner gets ForbiddenException
    /**
     * Validates: Requirements 2.2
     */
    @Property(tries = 20)
    void roomUpdateByNonOwnerThrowsForbidden(
            @ForAll("roomNames") String roomName,
            @ForAll("roomDescriptions") String desc,
            @ForAll("roomVisibilities") RoomVisibility vis
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User owner = userRepository.findById(
                userService.register("owner" + suffix + "@example.com", "owner" + suffix, "password123").id()
        ).orElseThrow();
        User nonOwner = userRepository.findById(
                userService.register("other" + suffix + "@example.com", "other" + suffix, "password123").id()
        ).orElseThrow();

        String uniqueName = roomName + "-" + UUID.randomUUID();
        Room room = roomService.getRoomById(
                roomService.createRoom(uniqueName, desc, vis, owner).id()
        );

        assertThatThrownBy(() -> roomService.updateRoom(room.getId(), nonOwner, "newname", "newdesc", vis))
                .isInstanceOf(ForbiddenException.class);
    }

    // Feature: ui-completion-and-fixes, Property 1: Duplicate name gets ConflictException
    /**
     * Validates: Requirements 2.2
     */
    @Property(tries = 20)
    void roomUpdateDuplicateNameThrowsConflict(
            @ForAll("roomNames") String roomName,
            @ForAll("roomDescriptions") String desc,
            @ForAll("roomVisibilities") RoomVisibility vis
    ) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User owner = userRepository.findById(
                userService.register("owner" + suffix + "@example.com", "owner" + suffix, "password123").id()
        ).orElseThrow();

        String name1 = roomName + "-" + UUID.randomUUID();
        String name2 = roomName + "-" + UUID.randomUUID();

        Room room1 = roomService.getRoomById(
                roomService.createRoom(name1, desc, vis, owner).id()
        );
        roomService.createRoom(name2, desc, vis, owner);

        // Trying to rename room1 to name2 should conflict
        assertThatThrownBy(() -> roomService.updateRoom(room1.getId(), owner, name2, desc, vis))
                .isInstanceOf(ConflictException.class);
    }
}
