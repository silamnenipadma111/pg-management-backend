package com.pgmanagement.support;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small test client: signs in like the website does (session cookie + CSRF token) and reads JSON responses. */
public class ApiClient {

    public static final String SESSION_COOKIE = "PG_SESSION";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String DEMO_PASSWORD = "Password@123";
    public static final String STRONG_PASSWORD = "Tr4vel!Light";

    /** What the browser keeps after signing in. */
    public record Session(Cookie cookie, String csrfToken, JsonNode user) {
    }

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;

    public ApiClient(MockMvc mvc, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
    }

    public Session signin(String email, String password, String role) throws Exception {
        MockHttpServletResponse response = perform(signinRequest(email, password, role))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie(SESSION_COOKIE);
        assertThat(cookie).as("session cookie").isNotNull();
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        return new Session(cookie, body.get("csrfToken").asString(), body.get("user"));
    }

    public MockHttpServletRequestBuilder signinRequest(String email, String password, String role) {
        return post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"role\":\"" + role + "\"}");
    }

    public MockHttpServletRequestBuilder signupRequest(String name, String email, String password, String role) {
        return post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"phone\":\"9000012345\","
                        + "\"password\":\"" + password + "\",\"role\":\"" + role + "\"}");
    }

    /** Signs up (no email verification in the default test setup) and signs in. */
    public Session signupAndSignin(String name, String email, String role) throws Exception {
        perform(signupRequest(name, email, STRONG_PASSWORD, role)).andExpect(status().isCreated());
        return signin(email, STRONG_PASSWORD, role);
    }

    /** Adds what the website sends with every authenticated call: the session cookie and the CSRF token. */
    public static MockHttpServletRequestBuilder as(Session session, MockHttpServletRequestBuilder request) {
        return request.cookie(session.cookie()).header(CSRF_HEADER, session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    public JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    public JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        return json(perform(request).andExpect(status().isOk()));
    }
}
