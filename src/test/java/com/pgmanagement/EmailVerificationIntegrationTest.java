package com.pgmanagement;

import com.pgmanagement.security.SessionService;
import com.pgmanagement.support.ApiClient;
import com.pgmanagement.support.ApiClient.Session;
import com.pgmanagement.support.ApiIntegrationTest;
import com.pgmanagement.support.Mails;
import com.pgmanagement.support.MutableClock;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static com.pgmanagement.support.ApiClient.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The flows that depend on real email: verification, password reset and the mails sent for bookings. */
@ApiIntegrationTest
@TestPropertySource(properties = {"app.auth.require-email-verification=true", "app.auth.cookie-secure=true"})
class EmailVerificationIntegrationTest {

    private static final MutableClock CLOCK = MutableClock.startingNow();
    private static final String CUSTOMER = "CUSTOMER";
    private static final String OWNER = "OWNER";
    private static final String CODE = "$.code";
    private static final String SESSION_URL = "/api/auth/session";
    private static final String SUPPORT_EMAIL = "support@pg-stay.in";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SessionService sessionService;
    @MockitoBean
    private JavaMailSender mailSender;
    @TestBean
    private Clock clock;

    private ApiClient api;

    static Clock clock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, objectMapper);
        when(mailSender.createMimeMessage()).thenAnswer(invocation -> Mails.blank());
    }

    @Test
    void anAccountMustBeVerifiedThroughTheEmailedLinkBeforeSignIn() throws Exception {
        String email = "verify-me@test.in";
        api.perform(api.signupRequest("Verify Me", email, ApiClient.STRONG_PASSWORD, CUSTOMER))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verificationRequired").value(true));
        MimeMessage mail = lastMailTo(email);
        assertThat(Mails.subject(mail)).isEqualTo("PG-STAY.IN - Confirm your email address");
        assertThat(Mails.from(mail).getAddress()).isEqualTo(SUPPORT_EMAIL);
        assertThat(Mails.replyTo(mail)).isEqualTo(SUPPORT_EMAIL);
        assertThat(Mails.plainText(mail)).contains("http://localhost:4200/verify-email?token=");
        String token = Mails.linkToken(mail);

        api.perform(api.signinRequest(email, ApiClient.STRONG_PASSWORD, CUSTOMER)).andExpect(status().isForbidden())
                .andExpect(jsonPath(CODE).value("EMAIL_NOT_VERIFIED"));

        // "resend" within the cool-down sends nothing; afterwards it replaces the first link
        resend(email);
        assertThat(mailsTo(email)).hasSize(1);
        CLOCK.advance(Duration.ofMinutes(3));
        resend(email);
        String newToken = Mails.linkToken(lastMailTo(email));
        assertThat(newToken).isNotEqualTo(token);
        verifyEmail(token).andExpect(status().isBadRequest()).andExpect(jsonPath(CODE).value("INVALID_TOKEN"));
        verifyEmail(newToken).andExpect(status().isOk());
        verifyEmail(newToken).andExpect(status().isBadRequest());

        api.perform(api.signinRequest(email, ApiClient.STRONG_PASSWORD, CUSTOMER)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Secure")));
    }

    @Test
    void passwordResetChangesThePasswordAndEndsEverySession() throws Exception {
        String email = "reset-me@test.in";
        Session session = verifiedUser("Reset Me", email, CUSTOMER);

        forgotPassword(email);
        String token = Mails.linkToken(lastMailTo(email));
        resetPassword(token, "weak").andExpect(status().isBadRequest());
        resetPassword(token, "Mango$Lassi9").andExpect(status().isOk());

        assertThat(Mails.subject(lastMailTo(email))).isEqualTo("PG-STAY.IN - Your password was changed");
        mvc.perform(get(SESSION_URL).cookie(session.cookie())).andExpect(status().isUnauthorized());
        resetPassword(token, "Other$Lassi9").andExpect(status().isBadRequest());
        api.perform(api.signinRequest(email, ApiClient.STRONG_PASSWORD, CUSTOMER)).andExpect(status().isUnauthorized());
        assertThat(api.signin(email, "Mango$Lassi9", CUSTOMER).user().get("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void sessionsExpireWhenIdleAndLockedAccountsUnlockAgain() throws Exception {
        String email = "idle-user@test.in";
        Session session = verifiedUser("Idle User", email, CUSTOMER);

        CLOCK.advance(Duration.ofHours(1));
        mvc.perform(get(SESSION_URL).cookie(session.cookie())).andExpect(status().isOk());
        CLOCK.advance(Duration.ofHours(2).plusMinutes(1));
        mvc.perform(get(SESSION_URL).cookie(session.cookie())).andExpect(status().isUnauthorized());

        for (int attempt = 0; attempt < 5; attempt++) {
            api.perform(api.signinRequest(email, "Wrong!Pass1", CUSTOMER)).andExpect(status().isUnauthorized());
        }
        api.perform(api.signinRequest(email, ApiClient.STRONG_PASSWORD, CUSTOMER)).andExpect(status().isTooManyRequests());
        CLOCK.advance(Duration.ofMinutes(16));
        Session fresh = api.signin(email, ApiClient.STRONG_PASSWORD, CUSTOMER);

        CLOCK.advance(Duration.ofDays(2));
        sessionService.purgeFinished();
        mvc.perform(get(SESSION_URL).cookie(fresh.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void everyBookingActionMailsBothTheCustomerAndTheOwner() throws Exception {
        String ownerEmail = "mail-owner@test.in";
        String customerEmail = "mail-customer@test.in";
        Session owner = verifiedUser("Mail Owner", ownerEmail, OWNER);
        Session customer = verifiedUser("Mail Customer", customerEmail, CUSTOMER);
        JsonNode pg = api.json(mvc.perform(as(owner, post("/api/owner/pgs")).content("""
                {"name":"Mail Test PG","addressLine":"1 Main Rd","area":"Miyapur","city":"Hyderabad",
                 "state":"Telangana","pincode":"500049","genderPreference":"ANY","facilities":["WIFI"],
                 "contactPhone":"9000000002","media":[{"mediaType":"IMAGE","url":"https://example.com/a.jpg"}],
                 "rooms":[{"floorNumber":1,"roomNumber":"101","sharingType":1,"ac":false,"attachedBathroom":true,
                 "dailyRent":400,"monthlyRent":7000}]}
                """)).andExpect(status().isCreated()));
        long bedId = pg.get("floors").get(0).get("rooms").get(0).get("beds").get(0).get("id").asLong();
        String request = "{\"bedId\":" + bedId + ",\"bookingType\":\"MONTHLY\",\"duration\":2,\"startDate\":\""
                + LocalDate.now().plusDays(1) + "\"}";

        long first = api.json(mvc.perform(as(customer, post("/api/bookings")).content(request))
                .andExpect(status().isCreated())).get("id").asLong();
        assertThat(subjectsTo(ownerEmail)).contains("PG-STAY.IN - New booking request");
        assertThat(subjectsTo(customerEmail)).contains("PG-STAY.IN - Booking request sent");
        mvc.perform(as(customer, post("/api/bookings/" + first + "/cancel"))).andExpect(status().isOk());
        assertThat(subjectsTo(ownerEmail)).contains("PG-STAY.IN - Booking cancelled");
        assertThat(subjectsTo(customerEmail)).contains("PG-STAY.IN - Booking cancelled");

        long second = api.json(mvc.perform(as(customer, post("/api/bookings")).content(request))
                .andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(as(owner, post("/api/owner/bookings/" + second + "/approve"))).andExpect(status().isOk());
        assertThat(subjectsTo(ownerEmail)).contains("PG-STAY.IN - Booking approved");
        assertThat(subjectsTo(customerEmail)).contains("PG-STAY.IN - Booking approved");

        long paymentId = api.getJson(get("/api/payments/my").cookie(customer.cookie())).get(0).get("id").asLong();
        mvc.perform(as(customer, post("/api/payments/" + paymentId + "/cash"))
                .content("{\"paidOn\":\"" + LocalDate.now() + "\",\"receivedBy\":\"Mail Owner\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(owner, post("/api/owner/payments/" + paymentId + "/reject")).content("{\"note\":\"Not received\"}"))
                .andExpect(status().isOk());
        String order = api.json(mvc.perform(as(customer, post("/api/payments/" + paymentId + "/checkout"))
                .content("{\"paymentMethod\":\"NET_BANKING\"}")).andExpect(status().isOk())).get("orderId").asString();
        mvc.perform(as(customer, post("/api/payments/" + paymentId + "/complete")).content("{\"orderId\":\"" + order
                + "\",\"paymentMethod\":\"NET_BANKING\",\"bank\":\"HDFC Bank\"}")).andExpect(status().isOk());

        assertThat(subjectsTo(ownerEmail)).contains("PG-STAY.IN - Cash payment - please confirm",
                "PG-STAY.IN - Cash payment not accepted", "PG-STAY.IN - Payment received");
        assertThat(subjectsTo(customerEmail)).contains("PG-STAY.IN - Cash payment submitted",
                "PG-STAY.IN - Cash payment not accepted", "PG-STAY.IN - Payment successful");
        assertThat(Mails.plainText(lastMailTo(customerEmail))).contains("http://localhost:4200/my-rents?tab=history");
    }

    @Test
    void enquiriesReachTheSupportMailboxWithTheSenderAsReplyTo() throws Exception {
        mvc.perform(post("/api/support/requests").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"Asha Rao\",\"phone\":\"9000011111\",\"email\":\"asha.rao@test.in\","
                        + "\"requestType\":\"REGISTER_PG\",\"message\":\"Please register my PG\"}"))
                .andExpect(status().isCreated());

        MimeMessage forwarded = lastMailTo(SUPPORT_EMAIL);
        assertThat(Mails.replyTo(forwarded)).isEqualTo("asha.rao@test.in");
        assertThat(Mails.subject(forwarded)).contains("New enquiry", "Register my PG");
        assertThat(Mails.plainText(forwarded)).contains("Asha Rao", "9000011111", "Please register my PG");
        assertThat(Mails.subject(lastMailTo("asha.rao@test.in"))).contains("We received your request");
    }

    /** Signs up, verifies through the emailed link and signs in. */
    private Session verifiedUser(String name, String email, String role) throws Exception {
        api.perform(api.signupRequest(name, email, ApiClient.STRONG_PASSWORD, role)).andExpect(status().isCreated());
        verifyEmail(Mails.linkToken(lastMailTo(email))).andExpect(status().isOk());
        return api.signin(email, ApiClient.STRONG_PASSWORD, role);
    }

    private org.springframework.test.web.servlet.ResultActions verifyEmail(String token) throws Exception {
        return mvc.perform(json(post("/api/auth/verify-email"), "{\"token\":\"" + token + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions resetPassword(String token, String password)
            throws Exception {
        return mvc.perform(json(post("/api/auth/reset-password"),
                "{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"));
    }

    private void resend(String email) throws Exception {
        mvc.perform(json(post("/api/auth/resend-verification"), "{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
    }

    private void forgotPassword(String email) throws Exception {
        mvc.perform(json(post("/api/auth/forgot-password"), "{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<MimeMessage> mailsTo(String email) {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().filter(mail -> email.equals(Mails.to(mail))).toList();
    }

    private MimeMessage lastMailTo(String email) {
        List<MimeMessage> mails = mailsTo(email);
        assertThat(mails).as("mails to %s", email).isNotEmpty();
        return mails.get(mails.size() - 1);
    }

    private List<String> subjectsTo(String email) {
        return mailsTo(email).stream().map(Mails::subject).toList();
    }
}
