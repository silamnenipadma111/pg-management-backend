package com.pgmanagement;

import com.pgmanagement.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public endpoints are throttled per client address when rate limiting is switched on (the default). */
@ApiIntegrationTest
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class RateLimitIntegrationTest {

    private static final String ENQUIRY = "{\"name\":\"Asha\",\"phone\":\"9000011111\",\"requestType\":\"CALLBACK\"}";

    @Autowired
    private MockMvc mvc;

    @Test
    void enquiriesFromOneClientAreLimitedWhileOtherClientsAndEndpointsKeepWorking() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(enquiryFrom("203.0.113.50")).andExpect(status().isCreated());
        }

        mvc.perform(enquiryFrom("203.0.113.50")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        mvc.perform(enquiryFrom("203.0.113.51")).andExpect(status().isCreated());
        mvc.perform(get("/api/pgs").with(request -> {
            request.setRemoteAddr("203.0.113.50");
            return request;
        })).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder enquiryFrom(String address) {
        return post("/api/support/requests").contentType(MediaType.APPLICATION_JSON).content(ENQUIRY)
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                });
    }
}
