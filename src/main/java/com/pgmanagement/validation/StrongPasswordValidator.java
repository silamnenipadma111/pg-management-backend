package com.pgmanagement.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    /** BCrypt only uses the first 72 bytes of a password. */
    private static final int MAX_BYTES = 72;

    /** Passwords that satisfy the character rules but are among the first an attacker tries (lower case). */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password@123", "password@1", "password1!", "password123!", "passw0rd!", "p@ssw0rd", "p@ssword1",
            "p@ssw0rd1", "admin@123", "admin@1234", "welcome@123", "welcome@1", "qwerty@123", "qwerty123!",
            "abc@1234", "abcd@1234", "test@1234", "test@123", "pass@1234", "pass@123", "india@123",
            "iloveyou@1", "changeme@1", "letmein@1", "monkey@123", "dragon@123", "hello@123", "user@1234");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null / blank is reported by @NotBlank
        return value == null || value.isEmpty() || isStrong(value);
    }

    public static boolean isStrong(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
            upper |= Character.isUpperCase(c);
            lower |= Character.isLowerCase(c);
            digit |= Character.isDigit(c);
            special |= !Character.isLetterOrDigit(c);
        }
        return upper && lower && digit && special && !COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT));
    }
}
