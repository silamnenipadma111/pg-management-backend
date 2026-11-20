package com.pgmanagement;

import com.pgmanagement.support.ApiClient;
import com.pgmanagement.support.ApiClient.Session;
import com.pgmanagement.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.pgmanagement.support.ApiClient.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sign-up, sign-in, session cookie, CSRF, sign-out, lock-out, roles, CORS and security headers end to end. */
@ApiIntegrationTest
class AuthFlowIntegrationTest {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String OWNER = "OWNER";
    private static final String SESSION_URL = "/api/auth/session";
    private static final String SIGNOUT_URL = "/api/auth/signout";
    private static final String READ_ALL_URL = "/api/notifications/read-all";
    private static final String CODE = "$.code";
    private static final String MESSAGE = "$.message";
    private static final String SITE_ORIGIN = "http://localhost:4200";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, objectMapper);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "password, Weak User, weak@test.in, 9000012345, password",
            "password, Common User, common@test.in, 9000012345, Password@123",
            "email, Bad Email, user@nodot, 9000012345, Tr4vel!Light",
            "phone, Bad Phone, phone@test.in, 12345, Tr4vel!Light",
            "name, <b>x</b>, name@test.in, 9000012345, Tr4vel!Light",
            "password, Samantha Ruth, sam@test.in, 9000012345, Samantha#2026"})
    void signupRejectsInvalidInput(String field, String name, String email, String phone, String password)
            throws Exception {
        String body = "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"phone\":\"" + phone
                + "\",\"password\":\"" + password + "\",\"role\":\"CUSTOMER\"}";

        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(MESSAGE).value(org.hamcrest.Matchers.containsString(field)));
    }

    @Test
    void signupRejectsDuplicatesStaffRolesAndMalformedBodies() throws Exception {
        api.perform(api.signupRequest("First User", "dup@test.in", ApiClient.STRONG_PASSWORD, CUSTOMER))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verificationRequired").value(false));
        api.perform(api.signupRequest("Second User", "DUP@test.in", ApiClient.STRONG_PASSWORD, OWNER))
                .andExpect(status().isConflict());
        api.perform(api.signupRequest("Care Agent", "agent@test.in", ApiClient.STRONG_PASSWORD, "SUPPORT"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(MESSAGE).value("The request body is missing or is not valid JSON."));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void signinSetsAnHttpOnlySameSiteCookieAndReturnsNoTokenInTheBody() throws Exception {
        MockHttpServletResponse response = api.perform(api.signinRequest("customer@pgstay.in",
                ApiClient.DEMO_PASSWORD, CUSTOMER)).andExpect(status().isOk()).andReturn().getResponse();

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("PG_SESSION=")
                .contains("HttpOnly", "SameSite=Strict", "Path=/api", "Max-Age=43200");
        assertThat(body.has("token")).isFalse();
        assertThat(body.get("csrfToken").asString()).hasSize(43);
        assertThat(body.get("user").get("email").asString()).isEqualTo("customer@pgstay.in");
        assertThat(body.get("user").has("passwordHash")).isFalse();
        assertThat(response.getContentAsString()).doesNotContain(response.getCookie(ApiClient.SESSION_COOKIE).getValue());
    }

    @Test
    void wrongCredentialsAndWrongRoleAreUnauthorized() throws Exception {
        api.perform(api.signinRequest("owner@pgstay.in", "Wrong!Pass1", OWNER)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath(MESSAGE).value("Invalid email or password"));
        api.perform(api.signinRequest("nobody@pgstay.in", ApiClient.DEMO_PASSWORD, OWNER))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath(MESSAGE).value("Invalid email or password"));
        api.perform(api.signinRequest("owner2@pgstay.in", ApiClient.DEMO_PASSWORD, CUSTOMER))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSessionEndpointRestoresTheSignedInUser() throws Exception {
        Session session = api.signin("customer2@pgstay.in", ApiClient.DEMO_PASSWORD, CUSTOMER);

        mvc.perform(get(SESSION_URL).cookie(session.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("customer2@pgstay.in"))
                .andExpect(jsonPath("$.csrfToken").value(session.csrfToken()));
        mvc.perform(get(SESSION_URL)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath(MESSAGE).value("Please sign in to continue"));
    }

    @Test
    void stateChangingRequestsNeedTheCsrfTokenOfTheSession() throws Exception {
        Session session = api.signin("customer@pgstay.in", ApiClient.DEMO_PASSWORD, CUSTOMER);

        mvc.perform(post(READ_ALL_URL).cookie(session.cookie())).andExpect(status().isForbidden())
                .andExpect(jsonPath(CODE).value("CSRF_TOKEN_INVALID"));
        mvc.perform(post(READ_ALL_URL).cookie(session.cookie()).header(ApiClient.CSRF_HEADER, "forged"))
                .andExpect(status().isForbidden()).andExpect(jsonPath(CODE).value("CSRF_TOKEN_INVALID"));
        mvc.perform(get("/api/notifications").cookie(session.cookie())).andExpect(status().isOk());
        mvc.perform(as(session, post(READ_ALL_URL))).andExpect(status().isOk());
    }

    @Test
    void signoutRevokesTheSessionOnTheServer() throws Exception {
        Session session = api.signin("owner2@pgstay.in", ApiClient.DEMO_PASSWORD, OWNER);

        mvc.perform(post(SIGNOUT_URL).cookie(session.cookie())).andExpect(status().isForbidden());
        mvc.perform(as(session, post(SIGNOUT_URL))).andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        mvc.perform(get(SESSION_URL).cookie(session.cookie())).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(post(SIGNOUT_URL)).andExpect(status().isNoContent());
    }

    @Test
    void signingInAgainEndsThePreviousSession() throws Exception {
        Session first = api.signin("support@pgstay.in", ApiClient.DEMO_PASSWORD, "SUPPORT");

        MockHttpServletResponse response = mvc.perform(as(first,
                        api.signinRequest("support@pgstay.in", ApiClient.DEMO_PASSWORD, "SUPPORT")))
                .andExpect(status().isOk()).andReturn().getResponse();

        mvc.perform(get(SESSION_URL).cookie(first.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(get(SESSION_URL).cookie(response.getCookie(ApiClient.SESSION_COOKIE))).andExpect(status().isOk());
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountTemporarily() throws Exception {
        api.perform(api.signupRequest("Lock Target", "lock-target@test.in", ApiClient.STRONG_PASSWORD, CUSTOMER))
                .andExpect(status().isCreated());
        for (int attempt = 0; attempt < 5; attempt++) {
            api.perform(api.signinRequest("lock-target@test.in", "Wrong!Pass1", CUSTOMER))
                    .andExpect(status().isUnauthorized());
        }

        api.perform(api.signinRequest("lock-target@test.in", ApiClient.STRONG_PASSWORD, CUSTOMER))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath(CODE).value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void forgotPasswordAnswersTheSameForKnownAndUnknownAddresses() throws Exception {
        String unknown = mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@test.in\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String known = mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"owner@pgstay.in\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(known).isEqualTo(unknown).contains("If an account exists");
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bogus\",\"password\":\"Tr4vel!Light\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath(CODE).value("INVALID_TOKEN"));
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bogus\"}")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "{0} may not call {1}")
    @CsvSource({
            "customer@pgstay.in, CUSTOMER, /api/owner/pgs",
            "customer@pgstay.in, CUSTOMER, /api/support/requests",
            "owner@pgstay.in, OWNER, /api/bookings/my",
            "owner@pgstay.in, OWNER, /api/payments/my",
            "support@pgstay.in, SUPPORT, /api/owner/dashboard"})
    void endpointsAreLimitedToTheirRole(String email, String role, String url) throws Exception {
        Session session = api.signin(email, ApiClient.DEMO_PASSWORD, role);

        mvc.perform(get(url).cookie(session.cookie())).andExpect(status().isForbidden())
                .andExpect(jsonPath(MESSAGE).value("You are not allowed to do this"));
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        mvc.perform(get("/api/pgs").secure(true)).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("max-age=31536000")));
    }

    @Test
    void corsAllowsTheWebsiteWithCredentialsAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/auth/signin").header(HttpHeaders.ORIGIN, SITE_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SITE_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(api.signinRequest("owner@pgstay.in", ApiClient.DEMO_PASSWORD, OWNER)
                        .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void enquiriesArePublicAndHandledByCustomerCare() throws Exception {
        String enquiry = "{\"name\":\"Asha\",\"phone\":\"9000011111\",\"email\":\"asha@test.in\","
                + "\"requestType\":\"GENERAL\",\"message\":\"Do you have PGs in Pune?\"}";
        long id = api.json(mvc.perform(post("/api/support/requests").contentType(MediaType.APPLICATION_JSON)
                .content(enquiry)).andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(post("/api/support/requests").contentType(MediaType.APPLICATION_JSON)
                .content(enquiry.replace("9000011111", "12"))).andExpect(status().isBadRequest());
        Session support = api.signin("support@pgstay.in", ApiClient.DEMO_PASSWORD, "SUPPORT");

        assertThat(api.getJson(get("/api/support/requests").cookie(support.cookie())).toString()).contains("Asha");
        mvc.perform(as(support, post("/api/support/requests/" + id + "/resolve"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(get("/api/nothing-here").cookie(support.cookie())).andExpect(status().isNotFound())
                .andExpect(jsonPath(MESSAGE).exists());
    }
}
