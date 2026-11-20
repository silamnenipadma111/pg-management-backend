package com.pgmanagement.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecureTokensTest {

    @Test
    void randomTokensAreUrlSafeAndCarry256BitsOfEntropy() {
        String token = SecureTokens.random();

        // 32 random bytes in unpadded base64url
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void randomTokensDoNotRepeat() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            tokens.add(SecureTokens.random());
        }

        assertThat(tokens).hasSize(500);
    }

    @Test
    void sha256HexMatchesTheKnownTestVector() {
        assertThat(SecureTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashIsStableAndDiffersPerInput() {
        String token = SecureTokens.random();

        assertThat(SecureTokens.sha256Hex(token)).hasSize(64).isEqualTo(SecureTokens.sha256Hex(token))
                .isNotEqualTo(SecureTokens.sha256Hex(token + "x"));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "secret, secret, true",
            "secret, Secret, false",
            "secret, secre, false",
            "secret, NULL, false",
            "NULL, secret, false",
            "NULL, NULL, false"}, nullValues = "NULL")
    void constantTimeEqualsOnlyAcceptsIdenticalNonNullValues(String expected, String actual, boolean equal) {
        assertThat(SecureTokens.constantTimeEquals(expected, actual)).isEqualTo(equal);
    }
}
