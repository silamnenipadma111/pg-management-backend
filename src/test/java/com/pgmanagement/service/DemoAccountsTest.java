package com.pgmanagement.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAccountsTest {

    private final DemoAccounts demoAccounts = new DemoAccounts();

    @ParameterizedTest
    @ValueSource(strings = {"owner@pgstay.in", "OWNER@PGSTAY.IN", "  Owner@pgstay.in "})
    void registeredAddressesAreRecognisedIgnoringCaseAndSpaces(String email) {
        demoAccounts.register(List.of(" Owner@PGstay.in", "customer@pgstay.in"));

        assertThat(demoAccounts.contains(email)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "owner@pg-stay.in", "someone@example.com"})
    void otherAddressesAreNotDemoAccounts(String email) {
        demoAccounts.register(List.of("owner@pgstay.in"));

        assertThat(demoAccounts.contains(email)).isFalse();
    }

    @Test
    void nothingIsADemoAccountUntilRegistered() {
        assertThat(demoAccounts.contains("owner@pgstay.in")).isFalse();
    }
}
