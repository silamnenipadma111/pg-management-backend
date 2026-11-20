package com.pgmanagement.exception;

import com.pgmanagement.dto.AuthDtos.EmailRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private static final String GENERIC_FAILURE = "Something went wrong. Please try again.";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final WebRequest webRequest = new ServletWebRequest(new MockHttpServletRequest());

    @Test
    void apiExceptionsKeepTheirStatusMessageAndCode() {
        ResponseEntity<ApiError> response = handler.handleApi(
                ApiException.forbidden("Please verify your email address", ApiException.CODE_EMAIL_NOT_VERIFIED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().message()).isEqualTo("Please verify your email address");
        assertThat(response.getBody().code()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void rateLimitErrorsTellTheClientWhenToRetry() {
        ResponseEntity<ApiError> response = handler.handleApi(ApiException.tooManyRequests("Too many attempts", 120));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("120");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().retryAfterSeconds()).isEqualTo(120L);
        assertThat(response.getBody().code()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    void badCredentialsShowTheMessageWrittenForTheUser() {
        ResponseEntity<ApiError> response = handler.handleBadCredentials(
                new BadCredentialsException("Invalid email or password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(messageOf(response)).isEqualTo("Invalid email or password");
    }

    @Test
    void otherAuthenticationFailuresDoNotExposeFrameworkWording() {
        ResponseEntity<ApiError> response = handler.handleAuthentication(new InsufficientAuthenticationException(
                "An Authentication object was not found in the SecurityContext"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(messageOf(response)).isEqualTo("Please sign in to continue");
    }

    @Test
    void accessDeniedBecomesForbidden() {
        ResponseEntity<ApiError> response = handler.handleAccessDenied(new AccessDeniedException("Access Denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(messageOf(response)).isEqualTo("You are not allowed to do this");
    }

    @Test
    void concurrentBedUpdatesBecomeAConflictWithAHelpfulMessage() {
        ResponseEntity<ApiError> response = handler.handleLock(
                new ObjectOptimisticLockingFailureException("com.pgmanagement.entity.Bed", 5L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(messageOf(response)).contains("refresh").doesNotContain("com.pgmanagement");
    }

    @Test
    void databaseConstraintErrorsNeverLeakSqlDetails() {
        ResponseEntity<ApiError> response = handler.handleIntegrity(new DataIntegrityViolationException(
                "could not execute statement", new IllegalStateException("ORA-00001: unique constraint (UK_USERS_EMAIL)")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(messageOf(response)).doesNotContain("ORA-", "UK_USERS_EMAIL", "statement");
    }

    @Test
    void constraintViolationsListThePropertyAndTheReason() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            ConstraintViolationException violation = new ConstraintViolationException(
                    factory.getValidator().validate(new EmailRequest("not-an-email")));

            ResponseEntity<ApiError> response = handler.handleConstraint(violation);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(messageOf(response)).startsWith("email: ");
        }
    }

    @Test
    void unexpectedErrorsAreAnsweredWithAGenericMessage() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new IllegalStateException("NullPointer at com.pgmanagement.service.BookingService:42 password=secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(messageOf(response)).isEqualTo(GENERIC_FAILURE);
    }

    @Test
    void invalidRequestBodiesListEveryFieldError() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "signupRequest");
        binding.addError(new FieldError("signupRequest", "email", "must be a valid email address"));
        binding.addError(new FieldError("signupRequest", "phone", "must be a valid 10 digit mobile number"));
        binding.addError(new ObjectError("signupRequest", "passwords do not match"));
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", String.class), 0);

        ResponseEntity<Object> response = handler.handleException(
                new MethodArgumentNotValidException(parameter, binding), webRequest);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ApiError) response.getBody()).message()).isEqualTo("email: must be a valid email address, "
                + "phone: must be a valid 10 digit mobile number, passwords do not match");
    }

    @Test
    void malformedJsonIsExplainedWithoutParserInternals() throws Exception {
        HttpMessageNotReadableException unreadable = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character ('}' (code 125)) at [Source: REDACTED]",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<Object> response = handler.handleException(unreadable, webRequest);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ApiError) response.getBody()).message())
                .isEqualTo("The request body is missing or is not valid JSON.");
    }

    @Test
    void standardMvcErrorsKeepTheirStatusAndHeaders() throws Exception {
        ResponseEntity<Object> wrongMethod = handler.handleException(
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST")), webRequest);
        ResponseEntity<Object> missingParameter = handler.handleException(
                new MissingServletRequestParameterException("lat", "double"), webRequest);

        assertThat(wrongMethod).isNotNull();
        assertThat(wrongMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(wrongMethod.getHeaders().getAllow()).isNotEmpty();
        assertThat(((ApiError) wrongMethod.getBody()).message()).contains("PUT");
        assertThat(missingParameter).isNotNull();
        assertThat(missingParameter.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ApiError) missingParameter.getBody()).message()).contains("lat");
    }

    @Test
    void oversizedUploadsGetAClearMessage() throws Exception {
        ResponseEntity<Object> response = handler.handleException(new MaxUploadSizeExceededException(52_428_800L),
                webRequest);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(((ApiError) response.getBody()).message()).contains("50 MB");
    }

    @Test
    void serverSideMvcFailuresAreAlsoGeneric() throws Exception {
        ResponseEntity<Object> response = handler.handleException(new AsyncRequestTimeoutException(), webRequest);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(((ApiError) response.getBody()).message()).isEqualTo(GENERIC_FAILURE);
    }

    private static String messageOf(ResponseEntity<ApiError> response) {
        assertThat(response.getBody()).isNotNull();
        return response.getBody().message();
    }

    /** Only used as the "controller method" reflected by {@link #invalidRequestBodiesListEveryFieldError()}. */
    @SuppressWarnings("unused")
    void sampleEndpoint(String body) {
        assertThat(body).isNotNull();
    }
}
