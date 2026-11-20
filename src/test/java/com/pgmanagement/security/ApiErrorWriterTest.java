package com.pgmanagement.security;

import com.pgmanagement.exception.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorWriterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final ApiErrorWriter writer = new ApiErrorWriter(objectMapper);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void writesStatusAndUtf8JsonBody() throws Exception {
        writer.write(response, HttpStatus.UNAUTHORIZED, "Please sign in to continue ₹", null);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("message").asString()).isEqualTo("Please sign in to continue ₹");
        assertThat(body.get("timestamp").asString()).isNotBlank();
        assertThat(body.has("code")).isFalse();
        assertThat(body.has("retryAfterSeconds")).isFalse();
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void includesTheMachineReadableCodeWhenThereIsOne() throws Exception {
        writer.write(response, HttpStatus.FORBIDDEN, "Your session could not be verified.", "CSRF_TOKEN_INVALID");

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("code").asString()).isEqualTo("CSRF_TOKEN_INVALID");
    }

    @Test
    void messagesWithQuotesStayValidJson() throws Exception {
        writer.write(response, HttpStatus.BAD_REQUEST, "Unknown value \"x\" \\ </script>", null);

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("message").asString()).isEqualTo("Unknown value \"x\" \\ </script>");
    }

    @Test
    void addsTheRetryAfterHeaderForRateLimitErrors() throws Exception {
        writer.write(response, ApiError.of(HttpStatus.TOO_MANY_REQUESTS, "Slow down", "TOO_MANY_ATTEMPTS", 42L));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        assertThat(body.get("retryAfterSeconds").asLong()).isEqualTo(42);
    }
}
