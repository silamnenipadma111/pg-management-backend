package com.pgmanagement.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StrongPasswordValidatorTest {

    private final StrongPasswordValidator validator = new StrongPasswordValidator();

    @ParameterizedTest
    @ValueSource(strings = {"Tr4vel!Light", "Aa1!aaaa", "Zebra#2026-Stay", "Mango$Lassi9", "नमस्तेAa1!xyz"})
    void acceptsPasswordsWithAllCharacterClasses(String password) {
        assertThat(StrongPasswordValidator.isStrong(password)).isTrue();
        assertThat(validator.isValid(password, null)).isTrue();
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("weakPasswords")
    void rejectsWeakPasswords(String password, String reason) {
        assertThat(StrongPasswordValidator.isStrong(password)).as(reason).isFalse();
    }

    static Stream<Arguments> weakPasswords() {
        return Stream.of(
                Arguments.of("Aa1!aaa", "seven characters is too short"),
                Arguments.of("Aa1!" + "a".repeat(61), "sixty-five characters is too long"),
                Arguments.of("travel!light4", "no uppercase letter"),
                Arguments.of("TRAVEL!LIGHT4", "no lowercase letter"),
                Arguments.of("Travel!Light", "no digit"),
                Arguments.of("Travel4Light", "no special character"),
                Arguments.of("Tr4vel! Light", "contains a space"),
                Arguments.of("Tr4vel!\tLight", "contains a tab"),
                Arguments.of("Password@123", "commonly used password"),
                Arguments.of("PASSWORD@123", "common password in upper case still has no lowercase"),
                Arguments.of("P@ssw0rd", "commonly used password with substitutions"),
                Arguments.of("Welcome@123", "commonly used password regardless of case"),
                Arguments.of("Aa1!" + "€".repeat(23), "more than the 72 bytes BCrypt can use"),
                Arguments.of(null, "null"));
    }

    @Test
    void acceptsTheShortestAndLongestAllowedLength() {
        String shortest = "Aa1!bcde";
        String longest = "Aa1!" + "b".repeat(StrongPasswordValidator.MAX_LENGTH - 4);

        assertThat(shortest).hasSize(StrongPasswordValidator.MIN_LENGTH);
        assertThat(StrongPasswordValidator.isStrong(shortest)).isTrue();
        assertThat(longest).hasSize(StrongPasswordValidator.MAX_LENGTH);
        assertThat(StrongPasswordValidator.isStrong(longest)).isTrue();
    }

    @Test
    void multiByteCharactersCountAgainstTheByteLimitNotTheCharacterLimit() {
        String seventyTwoBytes = "Aa1!" + "€".repeat(22) + "xy";

        assertThat(seventyTwoBytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(StrongPasswordValidator.isStrong(seventyTwoBytes)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {""})
    void leavesMissingValuesToTheNotBlankConstraint(String empty) {
        assertThat(validator.isValid(empty, null)).isTrue();
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void reportsWeakValuesThroughTheConstraintInterface() {
        assertThat(validator.isValid("weak", null)).isFalse();
    }
}
