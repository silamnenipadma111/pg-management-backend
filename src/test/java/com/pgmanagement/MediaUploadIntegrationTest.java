package com.pgmanagement;

import com.pgmanagement.support.ApiClient;
import com.pgmanagement.support.ApiClient.Session;
import com.pgmanagement.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uploading PG photos: owners and customer care only, real media only, served back publicly. */
@ApiIntegrationTest
class MediaUploadIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final String UPLOAD_URL = "/api/media/upload";

    @TempDir
    private static Path uploadDir;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.upload-dir", () -> uploadDir.toString());
    }

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, objectMapper);
    }

    @Test
    void ownersUploadPhotosThatAreThenServedPublicly() throws Exception {
        Session owner = api.signin("owner@pgstay.in", ApiClient.DEMO_PASSWORD, "OWNER");

        JsonNode uploaded = api.json(mvc.perform(upload(owner, "room.png", PNG)).andExpect(status().isOk()));

        assertThat(uploaded.get("mediaType").asString()).isEqualTo("IMAGE");
        assertThat(uploaded.get("url").asString()).startsWith("/uploads/").endsWith(".png");
        assertThat(uploadDir.resolve(uploaded.get("url").asString().substring("/uploads/".length()))).exists();
        mvc.perform(get(uploaded.get("url").asString())).andExpect(status().isOk()).andExpect(content().bytes(PNG));
    }

    @Test
    void disguisedOrUnsupportedFilesAreRejected() throws Exception {
        Session owner = api.signin("owner@pgstay.in", ApiClient.DEMO_PASSWORD, "OWNER");
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.US_ASCII);

        mvc.perform(upload(owner, "photo.png", html)).andExpect(status().isBadRequest());
        mvc.perform(upload(owner, "page.html", html)).andExpect(status().isBadRequest());
        mvc.perform(upload(owner, "empty.png", new byte[0])).andExpect(status().isBadRequest());
    }

    @Test
    void onlyOwnersAndCustomerCareMayUpload() throws Exception {
        Session customer = api.signin("customer@pgstay.in", ApiClient.DEMO_PASSWORD, "CUSTOMER");
        Session support = api.signin("support@pgstay.in", ApiClient.DEMO_PASSWORD, "SUPPORT");

        mvc.perform(upload(customer, "room.png", PNG)).andExpect(status().isForbidden());
        mvc.perform(multipart(UPLOAD_URL).file(new MockMultipartFile("file", "room.png", "image/png", PNG)))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(UPLOAD_URL).file(new MockMultipartFile("file", "room.png", "image/png", PNG))
                .cookie(support.cookie())).andExpect(status().isForbidden());
        mvc.perform(upload(support, "room.png", PNG)).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder upload(Session session, String fileName, byte[] bytes) {
        return multipart(UPLOAD_URL).file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes))
                .cookie(session.cookie()).header(ApiClient.CSRF_HEADER, session.csrfToken());
    }
}
