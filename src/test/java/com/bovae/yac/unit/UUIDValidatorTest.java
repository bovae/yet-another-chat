package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovae.yac.validation.UUIDValidator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Unit tests for {@link UUIDValidator}. */
class UUIDValidatorTest {

    private final UUIDValidator validator = new UUIDValidator();

    static Stream<Arguments> valueCases() {
        return Stream.of(
                Arguments.of(null, true),
                Arguments.of("123e4567-e89b-12d3-a456-426614174000", true),
                Arguments.of(UUID.randomUUID().toString(), true),
                Arguments.of("not-a-uuid", false),
                Arguments.of("", false),
                Arguments.of("123e4567-e89b-12d3-a456", false));
    }

    @ParameterizedTest(name = "value=\"{0}\" -> valid={1}")
    @MethodSource("valueCases")
    void isValid_shouldReturnExpected_whenValueVaries(String value, boolean expected) {
        assertThat(validator.isValid(value, null)).isEqualTo(expected);
    }
}
