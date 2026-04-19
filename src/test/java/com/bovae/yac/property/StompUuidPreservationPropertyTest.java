package com.bovae.yac.property;

import com.bovae.yac.model.dto.ChatMessageRequest;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeType;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation property tests for REST API and STOMP ObjectMapper behavior.
 *
 * These tests verify that a SNAKE_CASE-configured JsonMapper correctly round-trips
 * ChatMessageRequest and produces snake_case JSON keys. This captures the baseline
 * REST-layer behavior that MUST NOT regress after the STOMP fix.
 *
 * EXPECTED OUTCOME: All tests PASS on UNFIXED code — these are preservation tests.
 *
 * Validates: Requirements 3.1, 3.4
 */
class StompUuidPreservationPropertyTest {

    private static final MimeType APPLICATION_JSON = MimeType.valueOf("application/json");

    private static final JsonMapper SNAKE_CASE_MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private JacksonJsonMessageConverter createSnakeCaseConverter() {
        return new JacksonJsonMessageConverter(SNAKE_CASE_MAPPER);
    }

    private Message<byte[]> buildMessage(byte[] payload) {
        MessageHeaders headers = new MessageHeaders(
                Map.of(MessageHeaders.CONTENT_TYPE, APPLICATION_JSON)
        );
        return MessageBuilder.createMessage(payload, headers);
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> nonBlankContent() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<ChatMessageRequest> chatMessageRequests() {
        Arbitrary<UUID> roomIds = uuids();
        Arbitrary<String> contents = nonBlankContent();
        Arbitrary<UUID> replyToIds = uuids().injectNull(0.5);

        return Combinators.combine(roomIds, contents, replyToIds)
                .as(ChatMessageRequest::new);
    }

    // Property 2a: Round-trip preservation — serialize then deserialize produces equal object
    /**
     * **Validates: Requirements 3.1**
     *
     * For all randomly generated ChatMessageRequest values (random UUIDs, random non-blank
     * content, optional random replyToId), serializing with the SNAKE_CASE ObjectMapper
     * and deserializing back produces an equal ChatMessageRequest.
     *
     * This captures the REST-layer round-trip behavior that must not regress.
     */
    @Property(tries = 100)
    void snakeCaseRoundTripPreservesAllFields(
            @ForAll("chatMessageRequests") ChatMessageRequest original
    ) {
        byte[] serialized = SNAKE_CASE_MAPPER.writeValueAsBytes(original);
        Message<byte[]> message = buildMessage(serialized);

        JacksonJsonMessageConverter converter = createSnakeCaseConverter();
        Object result = converter.fromMessage(message, ChatMessageRequest.class);

        assertThat(result)
                .as("Deserialized result should not be null")
                .isNotNull()
                .isInstanceOf(ChatMessageRequest.class);

        ChatMessageRequest deserialized = (ChatMessageRequest) result;
        assertThat(deserialized.roomId())
                .as("roomId should survive round-trip")
                .isEqualTo(original.roomId());
        assertThat(deserialized.content())
                .as("content should survive round-trip")
                .isEqualTo(original.content());
        assertThat(deserialized.replyToId())
                .as("replyToId should survive round-trip")
                .isEqualTo(original.replyToId());
    }

    // Property 2b: Snake_case key format — serialized JSON uses snake_case, never camelCase
    /**
     * **Validates: Requirements 3.4**
     *
     * For all randomly generated ChatMessageRequest values, the SNAKE_CASE ObjectMapper
     * serialization output contains snake_case keys (room_id, content, reply_to_id)
     * and never camelCase keys (roomId, replyToId).
     */
    @Property(tries = 100)
    void snakeCaseSerializationProducesSnakeCaseKeys(
            @ForAll("chatMessageRequests") ChatMessageRequest original
    ) {
        String json = SNAKE_CASE_MAPPER.writeValueAsString(original);

        // Must contain snake_case keys
        assertThat(json)
                .as("Serialized JSON should contain snake_case key 'room_id'")
                .contains("\"room_id\"");
        assertThat(json)
                .as("Serialized JSON should contain key 'content'")
                .contains("\"content\"");

        // Must NOT contain camelCase keys
        assertThat(json)
                .as("Serialized JSON should NOT contain camelCase key 'roomId'")
                .doesNotContain("\"roomId\"");
        assertThat(json)
                .as("Serialized JSON should NOT contain camelCase key 'replyToId'")
                .doesNotContain("\"replyToId\"");

        // If replyToId is present, verify its snake_case key
        if (original.replyToId() != null) {
            assertThat(json)
                    .as("Serialized JSON should contain snake_case key 'reply_to_id' when replyToId is present")
                    .contains("\"reply_to_id\"");
        }
    }
}
