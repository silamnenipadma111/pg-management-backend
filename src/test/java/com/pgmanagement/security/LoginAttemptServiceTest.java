package com.pgmanagement.security;

import com.pgmanagement.support.MutableClock;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private static final int MAX_FAILURES = 3;
    private static final String EMAIL = "anil@example.com";
    private static final long LOCK_SECONDS = TestProps.LOCK_DURATION.toSeconds();

    private final MutableClock clock = MutableClock.fixed();
    private final LoginAttemptService service = new LoginAttemptService(
            TestProps.app(TestProps.auth("false", MAX_FAILURES, true)), clock);

    @Test
    void nothingIsLockedBeforeAnyFailure() {
        assertThat(service.secondsUntilUnlocked(EMAIL)).isZero();
    }

    @Test
    void locksTheAddressAfterTheConfiguredNumberOfFailures() {
        fail(EMAIL, MAX_FAILURES - 1);
        assertThat(service.secondsUntilUnlocked(EMAIL)).isZero();

        service.recordFailure(EMAIL);

        assertThat(service.secondsUntilUnlocked(EMAIL)).isEqualTo(LOCK_SECONDS);
    }

    @Test
    void theRemainingLockTimeCountsDown() {
        fail(EMAIL, MAX_FAILURES);

        clock.advance(Duration.ofMinutes(5));

        assertThat(service.secondsUntilUnlocked(EMAIL)).isEqualTo(LOCK_SECONDS - 300);
    }

    @Test
    void unlocksAfterTheLockDurationAndStartsCountingFromZero() {
        fail(EMAIL, MAX_FAILURES);

        clock.advance(TestProps.LOCK_DURATION);
        assertThat(service.secondsUntilUnlocked(EMAIL)).isZero();

        fail(EMAIL, MAX_FAILURES - 1);
        assertThat(service.secondsUntilUnlocked(EMAIL)).isZero();
        service.recordFailure(EMAIL);
        assertThat(service.secondsUntilUnlocked(EMAIL)).isPositive();
    }

    @Test
    void aSuccessfulSignInForgetsEarlierFailures() {
        fail(EMAIL, MAX_FAILURES - 1);

        service.recordSuccess(EMAIL);
        fail(EMAIL, MAX_FAILURES - 1);

        assertThat(service.secondsUntilUnlocked(EMAIL)).isZero();
    }

    @Test
    void aFailureDuringTheLockNeitherLiftsNorExtendsIt() {
        fail(EMAIL, MAX_FAILURES);
        clock.advance(Duration.ofMinutes(1));

        service.recordFailure(EMAIL);

        assertThat(service.secondsUntilUnlocked(EMAIL)).isEqualTo(LOCK_SECONDS - 60);
    }

    @Test
    void failuresOlderThanTheWindowNoLongerCount() {
        fail(EMAIL, MAX_FAILURES - 1);

        clock.advance(TestProps.LOCK_DURATION.plusSeconds(1));
        service.recordFailure(EMAIL);

        assertThat(service.secondsUntilUnlocked(EMAIL)).isZero();
    }

    @Test
    void addressesAreComparedIgnoringCaseAndSurroundingSpaces() {
        service.recordFailure("Anil@Example.com");
        service.recordFailure("  anil@example.com ");
        service.recordFailure("ANIL@EXAMPLE.COM");

        assertThat(service.secondsUntilUnlocked(EMAIL)).isPositive();
    }

    @Test
    void everyAddressIsTrackedSeparatelyEvenWhenNoAccountExistsForIt() {
        fail("nobody@nowhere.example", MAX_FAILURES);

        assertThat(service.secondsUntilUnlocked("nobody@nowhere.example")).isPositive();
        assertThat(service.secondsUntilUnlocked(EMAIL)).isZero();
    }

    @Test
    void aMissingAddressIsHandledLikeAnyOther() {
        fail(null, MAX_FAILURES);

        assertThat(service.secondsUntilUnlocked(null)).isPositive();
    }

    @Test
    void keepsWorkingWhenAVeryLargeNumberOfAddressesIsTracked() {
        for (int i = 0; i < 50_000; i++) {
            service.recordFailure("user" + i + "@flood.example");
        }
        clock.advance(TestProps.LOCK_DURATION.plusSeconds(1));

        fail(EMAIL, MAX_FAILURES);

        assertThat(service.secondsUntilUnlocked(EMAIL)).isEqualTo(LOCK_SECONDS);
        assertThat(service.secondsUntilUnlocked("user1@flood.example")).isZero();
    }

    private void fail(String email, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(email);
        }
    }
}
