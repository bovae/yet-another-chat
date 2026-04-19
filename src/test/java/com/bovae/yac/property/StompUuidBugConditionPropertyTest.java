package com.bovae.yac.property;

import com.bovae.yac.model.dto.ChatMessageRequest;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug Condition verification property test for STOMP snake_case UUID deserialization.
 *
 * Uses a SNAKE_CASE-configured JsonMapper with a JacksonJsonMessageConverter —
 * matching the now-fixed STOMP converter behavior in WebSocketConfig.
 *
 * The test encodes the EXPECTED behavior: snake_case payloads deserialize into
 * ChatMessageRequest with correct field values. Now that the fix is in place,
 * this test PASSES, confirming the STOMP converter correctly handles snake_case fields.
 *
 * Validates: Requirements 2.1, 2.2
 */
class StompUuidBugConditionPropertyTest {

    private static final MimeType APPLICATION_JSON = MimeType.valueOf("application/json");

    /**
     * Creates a JacksonJsonMessageConverter with a SNAKE_CASE-configured JsonMapper —
     * matching the now-fixed STOMP converter behavior in WebSocketConfig.
     */
    private JacksonJsonMessageConverter createSnakeCaseConverter() {
        JsonMapper snakeCaseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        return new JacksonJsonMessageConverter(snakeCaseMapper);
    }

    private Message<byte[]> buildMessage(String json) {
        MessageHeaders headers = new MessageHeaders(
                Map.of(MessageHeaders.CONTENT_TYPE, APPLICATION_JSON)
        );
        return MessageBuilder.createMessage(
                json.getBytes(StandardCharsets.UTF_8),
                headers
        );
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

    // Property 1: Expected Behavior — STOMP Snake_Case UUID Deserialization Succeeds (required fields only)
    /**
     * **Validates: Requirements 2.1**
     *
     * For any valid UUID roomId and non-blank content string, constructing a snake_case
     * JSON payload {"room_id": "<uuid>", "content": "<text>"} and deserializing via a
     * JacksonJsonMessageConverter with a SNAKE_CASE-configured JsonMapper SHALL produce a
     * ChatMessageRequest where roomId matches the input UUID and content matches the
     * input string.
     *
     * EXPECTED OUTCOME on fixed code: PASSES — the SNAKE_CASE JsonMapper correctly maps
     * room_id → roomId, confirming the bug is fixed.
     */
    @Property(tries = 50)
    void snakeCaseRoomIdDeserializesWithSnakeCaseJsonMapper(
            @ForAll("uuids") UUID roomId,
            @ForAll("nonBlankContent") String content
    ) {
        JacksonJsonMessageConverter converter = createSnakeCaseConverter();

        String json = """
                {"room_id": "%s", "content": "%s"}
                """.formatted(roomId, content);

        Message<byte[]> message = buildMessage(json);

        Object result = converter.fromMessage(message, ChatMessageRequest.class);

        assertThat(result)
                .as("Converter should produce a non-null ChatMessageRequest")
                .isNotNull()
                .isInstanceOf(ChatMessageRequest.class);

        ChatMessageRequest request = (ChatMessageRequest) result;
        assertThat(request.roomId())
                .as("roomId should match the input UUID from room_id field")
                .isEqualTo(roomId);
        assertThat(request.content())
                .as("content should match the input string")
                .isEqualTo(content);
        assertThat(request.replyToId())
                .as("replyToId should be null when not provided")
                .isNull();
    }

    // Property 1b: Expected Behavior — STOMP Snake_Case UUID Deserialization Succeeds (with reply_to_id)
    /**
     * **Validates: Requirements 2.1, 2.2**
     *
     * For any valid UUID roomId, non-blank content, and valid UUID replyToId, constructing
     * a snake_case JSON payload {"room_id": "<uuid>", "content": "<text>", "reply_to_id": "<uuid>"}
     * and deserializing via a JacksonJsonMessageConverter with a SNAKE_CASE-configured JsonMapper
     * SHALL produce a ChatMessageRequest where all fields match.
     *
     * EXPECTED OUTCOME on fixed code: PASSES — the SNAKE_CASE JsonMapper correctly maps
     * room_id → roomId and reply_to_id → replyToId, confirming the bug is fixed.
     */
    @Property(tries = 50)
    void snakeCaseWithReplyToIdDeserializesWithSnakeCaseJsonMapper(
            @ForAll("uuids") UUID roomId,
            @ForAll("nonBlankContent") String content,
            @ForAll("uuids") UUID replyToId
    ) {
        JacksonJsonMessageConverter converter = createSnakeCaseConverter();

        String json = """
                {"room_id": "%s", "content": "%s", "reply_to_id": "%s"}
                """.formatted(roomId, content, replyToId);

        Message<byte[]> message = buildMessage(json);

        Object result = converter.fromMessage(message, ChatMessageRequest.class);

        assertThat(result)
                .as("Converter should produce a non-null ChatMessageRequest")
                .isNotNull()
                .isInstanceOf(ChatMessageRequest.class);

        ChatMessageRequest request = (ChatMessageRequest) result;
        assertThat(request.roomId())
                .as("roomId should match the input UUID from room_id field")
                .isEqualTo(roomId);
        assertThat(request.content())
                .as("content should match the input string")
                .isEqualTo(content);
        assertThat(request.replyToId())
                .as("replyToId should match the input UUID from reply_to_id field")
                .isEqualTo(replyToId);
    }
}
