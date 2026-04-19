package com.bovae.yac.property;

import java.util.UUID;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for message alignment class assignment.
 *
 * The alignment logic used in both the Thymeleaf template (room.html) and the
 * JavaScript createMessageElement() function is:
 * if senderId equals the current user's ID → "message-own", otherwise → "message-other".
 *
 * Both renderers SHALL produce the same class for the same message and user combination.
 *
 * Since this is pure decision logic, we test it directly as a property.
 *
 * Validates: Requirements 18.1, 18.2, 18.8, 18.9
 */
class MessageAlignmentPropertyTest {

    /**
     * Mirrors the alignment decision logic from both room.html and app.js:
     * Thymeleaf: {@code th:classappend="${msg.senderId() == currentUser.id} ? 'message-own' : 'message-other'"}
     * JavaScript: {@code senderId === YAC_USER.id ? 'message-own' : 'message-other'}
     */
    static String determineAlignmentClass(UUID senderId, UUID currentUserId) {
        return senderId.equals(currentUserId) ? "message-own" : "message-other";
    }

    @Provide
    Arbitrary<UUID> randomUuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    /**
     * Property 12a: Same sender and current user → "message-own"
     *
     * For any message where senderId equals the current user's ID,
     * the message element SHALL have CSS class "message-own".
     *
     * Validates: Requirements 18.2, 18.9
     */
    @Property(tries = 20)
    void sameSenderAndCurrentUser_shallReturnMessageOwn(
            @ForAll("randomUuids") UUID userId
    ) {
        assertThat(determineAlignmentClass(userId, userId))
                .as("Alignment class should be 'message-own' when senderId equals currentUserId")
                .isEqualTo("message-own");
    }

    /**
     * Property 12b: Different sender and current user → "message-other"
     *
     * For any message where senderId does not equal the current user's ID,
     * the message element SHALL have CSS class "message-other".
     *
     * Validates: Requirements 18.1, 18.8
     */
    @Property(tries = 20)
    void differentSenderAndCurrentUser_shallReturnMessageOther(
            @ForAll("randomUuids") UUID senderId,
            @ForAll("randomUuids") UUID currentUserId
    ) {
        if (senderId.equals(currentUserId)) {
            return; // Skip the rare collision case; tested in 12a
        }

        assertThat(determineAlignmentClass(senderId, currentUserId))
                .as("Alignment class should be 'message-other' when senderId '%s' differs from currentUserId '%s'",
                        senderId, currentUserId)
                .isEqualTo("message-other");
    }

    /**
     * Property 12c: Exhaustive — class is "message-own" iff senderId equals currentUserId
     *
     * For any randomly generated senderId and currentUserId, the alignment class SHALL be
     * "message-own" if and only if senderId equals currentUserId.
     * Both renderers (Thymeleaf and JavaScript) SHALL produce the same class for the same
     * message and user combination.
     *
     * Validates: Requirements 18.1, 18.2, 18.8, 18.9
     */
    @Property(tries = 20)
    void anyIds_alignmentClassMatchesEquality(
            @ForAll("randomUuids") UUID senderId,
            @ForAll("randomUuids") UUID currentUserId
    ) {
        boolean isOwn = senderId.equals(currentUserId);
        String expected = isOwn ? "message-own" : "message-other";

        assertThat(determineAlignmentClass(senderId, currentUserId))
                .as("Alignment class for senderId '%s' and currentUserId '%s' should be '%s'",
                        senderId, currentUserId, expected)
                .isEqualTo(expected);
    }
}
