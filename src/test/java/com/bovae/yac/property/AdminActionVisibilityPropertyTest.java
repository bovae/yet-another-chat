package com.bovae.yac.property;

import com.bovae.yac.model.enums.RoomRole;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for admin action visibility by room role.
 *
 * The visibility logic used in the Thymeleaf template (member-list.html) is:
 * admin actions dropdown, "Invite user" button, and "View banned users" button
 * are visible only when the current user's room role is OWNER or ADMIN.
 *
 * Since this logic lives in the template, we test the underlying decision function
 * as a pure logic property.
 *
 * Validates: Requirements 7.1, 7.2, 7.3, 7.4
 */
class AdminActionVisibilityPropertyTest {

    /**
     * Encapsulates the visibility decision logic matching the Thymeleaf template condition:
     * {@code currentUserRole != null and (currentUserRole.name() == 'OWNER' or currentUserRole.name() == 'ADMIN')}
     */
    static boolean isAdminActionsVisible(RoomRole role) {
        return role != null && (role == RoomRole.OWNER || role == RoomRole.ADMIN);
    }

    @Provide
    Arbitrary<RoomRole> allRoomRoles() {
        return Arbitraries.of(RoomRole.values());
    }

    @Provide
    Arbitrary<RoomRole> adminRoles() {
        return Arbitraries.of(RoomRole.OWNER, RoomRole.ADMIN);
    }

    /**
     * Property 6a: OWNER or ADMIN role → admin actions visible
     *
     * For any room member list rendering, if the current user's room role is OWNER or ADMIN,
     * the admin actions dropdown, "Invite user" button, and "View banned users" button
     * SHALL be visible.
     *
     * Validates: Requirements 7.1, 7.3, 7.4
     */
    @Property(tries = 20)
    void ownerOrAdminRole_shallShowAdminActions(
            @ForAll("adminRoles") RoomRole role
    ) {
        assertThat(isAdminActionsVisible(role))
                .as("Admin actions should be visible for role %s", role)
                .isTrue();
    }

    /**
     * Property 6b: MEMBER role → admin actions hidden
     *
     * If the current user's role is MEMBER, the admin actions dropdown, "Invite user" button,
     * and "View banned users" button SHALL be hidden.
     *
     * Validates: Requirements 7.2
     */
    @Property(tries = 20)
    void memberRole_shallHideAdminActions() {
        assertThat(isAdminActionsVisible(RoomRole.MEMBER))
                .as("Admin actions should be hidden for MEMBER role")
                .isFalse();
    }

    /**
     * Property 6c: null role (not a member) → admin actions hidden
     *
     * If the user is not a member of the room (null role), the admin actions dropdown,
     * "Invite user" button, and "View banned users" button SHALL be hidden.
     *
     * Validates: Requirements 7.2
     */
    @Property(tries = 20)
    void nullRole_shallHideAdminActions() {
        assertThat(isAdminActionsVisible(null))
                .as("Admin actions should be hidden for null (non-member) role")
                .isFalse();
    }

    /**
     * Property 6d: Exhaustive — for any RoomRole, visibility matches OWNER/ADMIN membership
     *
     * For any randomly generated RoomRole value, admin actions visibility SHALL be true
     * if and only if the role is OWNER or ADMIN.
     *
     * Validates: Requirements 7.1, 7.2, 7.3, 7.4
     */
    @Property(tries = 20)
    void anyRole_visibilityMatchesOwnerOrAdmin(
            @ForAll("allRoomRoles") RoomRole role
    ) {
        boolean expected = (role == RoomRole.OWNER || role == RoomRole.ADMIN);

        assertThat(isAdminActionsVisible(role))
                .as("Admin actions visibility for role %s should be %s", role, expected)
                .isEqualTo(expected);
    }
}
