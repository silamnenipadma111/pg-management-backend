package com.pgmanagement.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * JSON body of every error response.
 *
 * @param code              optional machine readable reason the website reacts to (e.g. EMAIL_NOT_VERIFIED)
 * @param retryAfterSeconds set for 429 responses
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String message, String code, Long retryAfterSeconds, String timestamp) {

    public static ApiError of(HttpStatus status, String message) {
        return of(status, message, null, null);
    }

    public static ApiError of(HttpStatus status, String message, String code, Long retryAfterSeconds) {
        return new ApiError(status.value(), message == null || message.isBlank() ? status.getReasonPhrase() : message,
                code, retryAfterSeconds, LocalDateTime.now().toString());
    }
}
