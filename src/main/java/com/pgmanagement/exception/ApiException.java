package com.pgmanagement.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    public static final String CODE_EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String CODE_TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";
    public static final String CODE_INVALID_TOKEN = "INVALID_TOKEN";

    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null, null);
    }

    public ApiException(HttpStatus status, String message, String code, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException badRequest(String message, String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, code, null);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException forbidden(String message, String code) {
        return new ApiException(HttpStatus.FORBIDDEN, message, code, null);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException tooManyRequests(String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message, CODE_TOO_MANY_ATTEMPTS, retryAfterSeconds);
    }
}
