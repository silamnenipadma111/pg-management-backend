package com.pgmanagement.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorTest {

    @Test
    void carriesStatusMessageAndAParseableTimestamp() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        ApiError error = ApiError.of(HttpStatus.CONFLICT, "Bed 101-A is not available");

        assertThat(error.status()).isEqualTo(409);
        assertThat(error.message()).isEqualTo("Bed 101-A is not available");
        assertThat(error.code()).isNull();
        assertThat(error.retryAfterSeconds()).isNull();
        assertThat(LocalDateTime.parse(error.timestamp())).isAfter(before);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void fallsBackToTheStatusTextWhenThereIsNoMessage(String message) {
        assertThat(ApiError.of(HttpStatus.NOT_FOUND, message).message()).isEqualTo("Not Found");
    }

    @Test
    void keepsTheCodeAndRetryDelay() {
        ApiError error = ApiError.of(HttpStatus.TOO_MANY_REQUESTS, "Slow down", "TOO_MANY_ATTEMPTS", 30L);

        assertThat(error.code()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(error.retryAfterSeconds()).isEqualTo(30L);
    }

    @Test
    void exceptionFactoriesChooseTheMatchingHttpStatus() {
        assertThat(ApiException.notFound("PG")).hasMessage("PG not found")
                .extracting(ApiException::getStatus).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ApiException.badRequest("bad").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ApiException.forbidden("no").getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ApiException.conflict("taken").getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new ApiException(HttpStatus.GONE, "gone")).hasMessage("gone")
                .extracting(ApiException::getCode, ApiException::getRetryAfterSeconds).containsExactly(null, null);
    }

    @Test
    void exceptionFactoriesCarryMachineReadableCodes() {
        ApiException invalidToken = ApiException.badRequest("expired", ApiException.CODE_INVALID_TOKEN);
        ApiException unverified = ApiException.forbidden("verify first", ApiException.CODE_EMAIL_NOT_VERIFIED);
        ApiException throttled = ApiException.tooManyRequests("wait", 90);

        assertThat(invalidToken.getCode()).isEqualTo("INVALID_TOKEN");
        assertThat(invalidToken.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unverified.getCode()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(unverified.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(throttled.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(throttled.getRetryAfterSeconds()).isEqualTo(90L);
    }
}
