package com.bovae.yac.property;

import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.enums.RoomVisibility;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug Condition exploration property test for Thymeleaf UUID inline serialization.
 *
 * Thymeleaf 3.1.3's {@code th:inline="javascript"} serializes {@code java.util.UUID}
 * as a JavaBean object ({@code {"mostSignificantBits":...,"leastSignificantBits":...}})
 * instead of a quoted UUID string, because Jackson 2 is absent (project uses Jackson 3).
 *
 * This test encodes the EXPECTED behavior: the rendered {@code window.YAC_ROOM.id} should
 * be a quoted UUID string. On UNFIXED code ({@code [[${room.id}]]} without {@code .toString()}),
 * this test is EXPECTED TO FAIL, confirming the bug exists.
 *
 * Validates: Requirements 1.1, 2.1
 */
class ThymeleafUuidBugConditionPropertyTest {

    private static final Pattern UUID_REGEX = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );

    /**
     * Extracts the value of the {@code id} field from the rendered
     * {@code window.YAC_ROOM = { id: ..., ... }} JavaScript block.
     *
     * Captures everything between {@code id:} and the next comma or closing brace.
     */
    private static final Pattern ID_FIELD_PATTERN = Pattern.compile(
            "id:\\s*(.+?)\\s*[,}]"
    );

    private final SpringTemplateEngine templateEngine;

    ThymeleafUuidBugConditionPropertyTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        this.templateEngine = new SpringTemplateEngine();
        this.templateEngine.setTemplateResolver(resolver);
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    /**
     * Property 1: Bug Condition — Thymeleaf UUID Inline Serialization Bug
     *
     * <b>Validates: Requirements 1.1, 2.1</b>
     *
     * For any {@code java.util.UUID} value, rendering the {@code th:inline="javascript"}
     * block from the test template (which mirrors room.html's {@code [[${room.id}]]}
     * expression) SHALL produce a quoted UUID string matching the standard UUID format.
     *
     * EXPECTED OUTCOME on UNFIXED code: FAILS — the rendered output contains
     * {@code {"mostSignificantBits":...,"leastSignificantBits":...}} instead of a UUID string,
     * confirming the bug exists.
     */
    @Property(tries = 10)
    void uuidRendersAsQuotedStringInThymeleafInlineJavaScript(
            @ForAll("uuids") UUID uuid
    ) {
        Room room = Room.builder()
                .id(uuid)
                .name("test-room")
                .visibility(RoomVisibility.PUBLIC)
                .build();

        Context context = new Context();
        context.setVariable("room", room);
        context.setVariable("nextCursor", 1L);
        context.setVariable("hasMore", false);

        String rendered = templateEngine.process("test/uuid-inline", context);

        Matcher idMatcher = ID_FIELD_PATTERN.matcher(rendered);
        assertThat(idMatcher.find())
                .as("Should find the id field in the rendered window.YAC_ROOM block")
                .isTrue();

        String idValue = idMatcher.group(1).trim();

        // The id value should be a quoted string like "550e8400-e29b-41d4-a716-446655440000"
        // and NOT an object like {"mostSignificantBits":...,"leastSignificantBits":...}
        assertThat(idValue)
                .as("UUID should render as a quoted string, not as an object with mostSignificantBits/leastSignificantBits")
                .doesNotContain("mostSignificantBits")
                .doesNotContain("leastSignificantBits");

        // Strip surrounding quotes and verify UUID format
        assertThat(idValue)
                .as("UUID should be wrapped in quotes")
                .startsWith("\"")
                .endsWith("\"");

        String unquoted = idValue.substring(1, idValue.length() - 1);
        assertThat(unquoted)
                .as("Unquoted value should match the standard UUID regex pattern")
                .matches(UUID_REGEX.pattern());

        assertThat(unquoted)
                .as("Rendered UUID string should match the input UUID")
                .isEqualTo(uuid.toString());
    }
}
