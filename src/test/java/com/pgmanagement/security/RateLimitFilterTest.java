package com.pgmanagement.security;

import com.pgmanagement.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static final String POST = "POST";
    private static final String ENQUIRY_PATH = "/api/support/requests";
    private static final int ENQUIRY_LIMIT = 5;
    private static final String CLIENT = "203.0.113.7";
    private static final int TOO_MANY_REQUESTS = 429;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final MutableClock clock = MutableClock.fixed();
    private final RateLimitFilter filter = new RateLimitFilter(new FixedWindowRateLimiter(clock, 10_000),
            new ApiErrorWriter(objectMapper), true);

    static List<RateLimitFilter.Rule> rules() {
        return RateLimitFilter.RULES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void everyRuleLetsItsLimitThroughAndBlocksTheNextRequest(RateLimitFilter.Rule rule) throws Exception {
        String path = rule.path().endsWith("/") ? rule.path() + "reverse" : rule.path();
        for (int i = 0; i < rule.limit(); i++) {
            assertThat(call(rule.method(), path, CLIENT).getStatus()).as("request %d", i + 1).isEqualTo(200);
        }

        assertThat(call(rule.method(), path, CLIENT).getStatus()).isEqualTo(TOO_MANY_REQUESTS);
    }

    @Test
    void aBlockedRequestGetsTheStandardErrorBodyAndRetryAfterHeader() throws Exception {
        exhaust(ENQUIRY_PATH, CLIENT);
        clock.advance(Duration.ofMinutes(5));

        MockHttpServletResponse blocked = call(POST, ENQUIRY_PATH, CLIENT);

        JsonNode body = objectMapper.readTree(blocked.getContentAsString());
        assertThat(blocked.getStatus()).isEqualTo(TOO_MANY_REQUESTS);
        assertThat(blocked.getContentType()).startsWith("application/json");
        assertThat(blocked.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("600");
        assertThat(body.get("status").asInt()).isEqualTo(TOO_MANY_REQUESTS);
        assertThat(body.get("code").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(body.get("retryAfterSeconds").asLong()).isEqualTo(600);
        assertThat(body.get("message").asString()).contains("Too many requests");
    }

    @Test
    void aBlockedRequestNeverReachesTheApplication() throws Exception {
        exhaust(ENQUIRY_PATH, CLIENT);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(POST, ENQUIRY_PATH, CLIENT), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theBudgetIsPerClientAddress() throws Exception {
        exhaust(ENQUIRY_PATH, CLIENT);

        assertThat(call(POST, ENQUIRY_PATH, "198.51.100.20").getStatus()).isEqualTo(200);
        assertThat(call(POST, ENQUIRY_PATH, CLIENT).getStatus()).isEqualTo(TOO_MANY_REQUESTS);
    }

    @Test
    void theBudgetIsRestoredWhenTheWindowIsOver() throws Exception {
        exhaust(ENQUIRY_PATH, CLIENT);

        clock.advance(Duration.ofMinutes(15));

        assertThat(call(POST, ENQUIRY_PATH, CLIENT).getStatus()).isEqualTo(200);
    }

    @Test
    void otherMethodsAndPathsAreNotLimited() throws Exception {
        for (int i = 0; i < 200; i++) {
            assertThat(call("GET", "/api/pgs", CLIENT).getStatus()).isEqualTo(200);
        }
        exhaust(ENQUIRY_PATH, CLIENT);

        assertThat(call("GET", ENQUIRY_PATH, CLIENT).getStatus()).as("listing is a different method").isEqualTo(200);
    }

    @Test
    void theContextPathIsIgnoredWhenMatchingRules() throws Exception {
        for (int i = 0; i <= ENQUIRY_LIMIT; i++) {
            MockHttpServletRequest request = request(POST, "/app" + ENQUIRY_PATH, CLIENT);
            request.setContextPath("/app");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(i < ENQUIRY_LIMIT ? 200 : TOO_MANY_REQUESTS);
        }
    }

    @Test
    void aDisabledFilterLetsEverythingThrough() throws Exception {
        RateLimitFilter disabled = new RateLimitFilter(new FixedWindowRateLimiter(clock, 10_000),
                new ApiErrorWriter(objectMapper), false);
        for (int i = 0; i < ENQUIRY_LIMIT * 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            disabled.doFilter(request(POST, ENQUIRY_PATH, CLIENT), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void theRuleTableCoversThePublicEndpointsThatAttractAbuse() {
        assertThat(RateLimitFilter.RULES).extracting(RateLimitFilter.Rule::path).contains(
                "/api/auth/signin", "/api/auth/signup", "/api/auth/resend-verification",
                "/api/auth/forgot-password", "/api/auth/reset-password", ENQUIRY_PATH, "/api/geo/");
        assertThat(RateLimitFilter.RULES).allSatisfy(rule -> {
            assertThat(rule.limit()).isPositive();
            assertThat(rule.window()).isPositive();
        });
    }

    private void exhaust(String path, String client) throws Exception {
        for (int i = 0; i < ENQUIRY_LIMIT; i++) {
            call(POST, path, client);
        }
    }

    private MockHttpServletResponse call(String method, String path, String client) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(method, path, client), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String client) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(client);
        return request;
    }
}
