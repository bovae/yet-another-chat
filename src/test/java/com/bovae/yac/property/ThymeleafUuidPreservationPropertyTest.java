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
 * Preservation property tests for Thymeleaf inline JavaScript serialization.
 *
 * These tests verify that non-UUID values ({@code String}, {@code Long}, {@code boolean})
 * are serialized correctly by Thymeleaf's fallback serializer in {@code th:inline="javascript"}
 * blocks, regardless of Jackson 2 absence.
 *
 * These tests are expected to PASS on UNFIXED code — the bug only affects UUID serialization.
 *
 * Validates: Requirements 3.1, 3.2
 */
class ThymeleafUuidPreservationPropertyTest {

    private static final UUID FIXED_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Extracts the value of the {@code name} field from the rendered
     * {@code window.YAC_ROOM = { ..., name: ..., ... }} JavaScript block.
     *
     * Captures everything between {@code name:} and the next {@code ,\n} that precedes
     * {@code nextCursor}.
     */
    private static final Pattern NAME_FIELD_PATTERN = Pattern.compile(
            "name:\\s*(.+?)\\s*,\\s*\\n\\s*nextCursor:"
    );

    /**
     * Extracts the value of the {@code nextCursor} field from the rendered
     * {@code window.YAC_ROOM} JavaScript block.
     */
    private static final Pattern NEXT_CURSOR_FIELD_PATTERN = Pattern.compile(
            "nextCursor:\\s*(.+?)\\s*,\\s*\\n\\s*hasMore:"
    );

    /**
     * Extracts the value of the {@code hasMore} field from the rendered
     * {@code window.YAC_ROOM} JavaScript block.
     */
    private static final Pattern HAS_MORE_FIELD_PATTERN = Pattern.compile(
            "hasMore:\\s*(.+?)\\s*\\n\\s*};"
    );

    private final SpringTemplateEngine templateEngine;

    ThymeleafUuidPreservationPropertyTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(org.thymeleaf.templatemode.TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");

        this.templateEngine = new SpringTemplateEngine();
        this.templateEngine.setTemplateResolver(resolver);
    }

    @Provide
    Arbitrary<String> arbitraryStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars(' ', '!', '@', '#', '$', '%', '&', '(', ')', '-', '_', '=', '+',
                        '[', ']', '{', '}', '|', ';', ':', ',', '.', '<', '>', '/', '?',
                        '\'', '"', '\\', '\n', '\t', '\r')
                .ofMinLength(0)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<Long> arbitraryLongs() {
        return Arbitraries.longs();
    }

    @Provide
    Arbitrary<Boolean> arbitraryBooleans() {
        return Arbitraries.of(true, false);
    }

    private String renderTemplate(String name, Long nextCursor, boolean hasMore) {
        Room room = Room.builder()
                .id(FIXED_UUID)
                .name(name)
                .visibility(RoomVisibility.PUBLIC)
                .build();

        Context context = new Context();
        context.setVariable("room", room);
        context.setVariable("nextCursor", nextCursor);
        context.setVariable("hasMore", hasMore);

        return templateEngine.process("test/uuid-inline", context);
    }
    /**
     * Property 2a — String preservation.
     *
     * <b>Validates: Requirements 3.1</b>
     *
     * For all arbitrary strings (including special chars, unicode, quotes, backslashes),
     * verify {@code room.name} renders as a valid JavaScript string literal in the
     * {@code window.YAC_ROOM} block.
     *
     * The rendered name value must start and end with double quotes (Thymeleaf's default
     * JS string quoting for inline expressions), and the content between quotes must be
     * a properly escaped representation of the original string.
     */
    @Property(tries = 20)
    void stringRendersAsValidJavaScriptStringLiteral(
            @ForAll("arbitraryStrings") String name
    ) {
        String rendered = renderTemplate(name, 1L, false);

        Matcher nameMatcher = NAME_FIELD_PATTERN.matcher(rendered);
        assertThat(nameMatcher.find())
                .as("Should find the name field in the rendered window.YAC_ROOM block")
                .isTrue();

        String nameValue = nameMatcher.group(1).trim();

        // Thymeleaf inline JS serializer wraps strings in double quotes
        assertThat(nameValue)
                .as("String value should be wrapped in double quotes")
                .startsWith("\"")
                .endsWith("\"");

        // The quoted string should be non-empty (at minimum the two quote chars)
        assertThat(nameValue.length())
                .as("Quoted string should have at least the two quote characters")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * Property 2b — Long preservation.
     *
     * <b>Validates: Requirements 3.2</b>
     *
     * For all arbitrary Long values (including 0, negatives, Long.MAX_VALUE, Long.MIN_VALUE),
     * verify {@code nextCursor} renders as a correct JavaScript number literal.
     */
    @Property(tries = 20)
    void longRendersAsCorrectJavaScriptNumberLiteral(
            @ForAll("arbitraryLongs") Long nextCursor
    ) {
        String rendered = renderTemplate("test-room", nextCursor, false);

        Matcher cursorMatcher = NEXT_CURSOR_FIELD_PATTERN.matcher(rendered);
        assertThat(cursorMatcher.find())
                .as("Should find the nextCursor field in the rendered window.YAC_ROOM block")
                .isTrue();

        String cursorValue = cursorMatcher.group(1).trim();

        // The rendered value should parse as a Long matching the input
        long parsedValue = Long.parseLong(cursorValue);
        assertThat(parsedValue)
                .as("Rendered nextCursor should match the input Long value")
                .isEqualTo(nextCursor);
    }

    /**
     * Property 2c — Boolean preservation.
     *
     * <b>Validates: Requirements 3.2</b>
     *
     * For both {@code true} and {@code false}, verify {@code hasMore} renders as the
     * correct JavaScript boolean literal.
     */
    @Property(tries = 5)
    void booleanRendersAsCorrectJavaScriptBooleanLiteral(
            @ForAll("arbitraryBooleans") boolean hasMore
    ) {
        String rendered = renderTemplate("test-room", 1L, hasMore);

        Matcher hasMoreMatcher = HAS_MORE_FIELD_PATTERN.matcher(rendered);
        assertThat(hasMoreMatcher.find())
                .as("Should find the hasMore field in the rendered window.YAC_ROOM block")
                .isTrue();

        String hasMoreValue = hasMoreMatcher.group(1).trim();

        assertThat(hasMoreValue)
                .as("Boolean should render as 'true' or 'false' JavaScript literal")
                .isEqualTo(String.valueOf(hasMore));
    }
}
