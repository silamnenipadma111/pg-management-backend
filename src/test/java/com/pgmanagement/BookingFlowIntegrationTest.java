package com.pgmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class BookingFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @Test
    void nearbySearchSortsByDistanceAndFiltersByRadius() throws Exception {
        JsonNode list = json(mvc.perform(get("/api/pgs").param("lat", "17.4486").param("lng", "78.3908")
                .param("radiusKm", "10")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.size()).isGreaterThan(0);
        assertThat(list.get(0).get("area").asText()).isEqualTo("Madhapur");
        list.forEach(p -> assertThat(p.get("distanceKm").asDouble()).isLessThanOrEqualTo(10));

        JsonNode female = json(mvc.perform(get("/api/pgs").param("gender", "FEMALE").param("sharing", "2"))
                .andReturn().getResponse().getContentAsString());
        female.forEach(p -> assertThat(p.get("genderPreference").asText()).isEqualTo("FEMALE"));
    }

    @Test
    void signinRejectsWrongRole() throws Exception {
        mvc.perform(post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@pgstay.in\",\"password\":\"Password@123\",\"role\":\"CUSTOMER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullBookingLifecycleBlocksThenBooksBedAndIssuesInvoice() throws Exception {
        String customer = signup("Test Customer", "flow-customer@test.in", "CUSTOMER");
        String owner = signin("owner@pgstay.in", "OWNER");

        JsonNode pgs = json(mvc.perform(get("/api/pgs").param("q", "Sri Sai")).andReturn().getResponse()
                .getContentAsString());
        long pgId = pgs.get(0).get("id").asLong();
        JsonNode detail = json(mvc.perform(get("/api/pgs/" + pgId)).andReturn().getResponse().getContentAsString());
        JsonNode bed = null;
        for (JsonNode floor : detail.get("floors")) {
            for (JsonNode room : floor.get("rooms")) {
                for (JsonNode b : room.get("beds")) {
                    if (bed == null && "AVAILABLE".equals(b.get("status").asText())) {
                        bed = b;
                    }
                }
            }
        }
        assertThat(bed).isNotNull();
        long bedId = bed.get("id").asLong();

        String body = "{\"bedId\":" + bedId + ",\"bookingType\":\"YEARLY\",\"paymentPlan\":\"MONTHLY\",\"duration\":1,"
                + "\"startDate\":\"" + LocalDate.now().plusDays(1) + "\"}";
        JsonNode booking = json(mvc.perform(auth(post("/api/bookings"), customer).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(booking.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(booking.get("bedStatus").asText()).isEqualTo("BLOCKED");

        // second request on the same bed must fail while it is blocked
        mvc.perform(auth(post("/api/bookings"), customer).content(body)).andExpect(status().isConflict());

        long bookingId = booking.get("id").asLong();
        JsonNode approved = json(mvc.perform(auth(post("/api/owner/bookings/" + bookingId + "/approve"), owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(approved.get("totalInstallments").asInt()).isEqualTo(12);

        JsonNode upcoming = json(mvc.perform(auth(get("/api/payments/my"), customer)).andReturn().getResponse()
                .getContentAsString());
        long paymentId = upcoming.get(0).get("id").asLong();
        // cannot pay the 2nd installment before the first
        mvc.perform(auth(post("/api/payments/" + upcoming.get(1).get("id").asLong() + "/pay"), customer)
                .content("{\"paymentMethod\":\"UPI\"}")).andExpect(status().isBadRequest());

        mvc.perform(auth(post("/api/payments/" + paymentId + "/pay"), customer)
                .content("{\"paymentMethod\":\"UPI\",\"transactionRef\":\"UPI123\"}")).andExpect(status().isOk());
        JsonNode confirmed = json(mvc.perform(auth(post("/api/owner/payments/" + paymentId + "/confirm"), owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(confirmed.get("status").asText()).isEqualTo("PAID");
        assertThat(confirmed.get("bookingStatus").asText()).isEqualTo("BOOKED");

        JsonNode invoice = json(mvc.perform(auth(get("/api/payments/" + paymentId + "/invoice"), customer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(invoice.get("invoiceNumber").asText()).startsWith("INV-");

        JsonNode myBookings = json(mvc.perform(auth(get("/api/bookings/my"), customer)).andReturn().getResponse()
                .getContentAsString());
        assertThat(myBookings.get(0).get("bedStatus").asText()).isEqualTo("BOOKED");

        JsonNode notifications = json(mvc.perform(auth(get("/api/notifications"), owner)).andReturn().getResponse()
                .getContentAsString());
        assertThat(notifications.get("items").toString()).contains("flow-customer@test.in");
    }

    @Test
    void customerCareRegistersPgForNewOwner() throws Exception {
        String support = signin("support@pgstay.in", "SUPPORT");
        String body = """
                {"ownerName":"Phone Owner","ownerEmail":"phone-owner@test.in","ownerPhone":"9000000001",
                 "pg":{"name":"Call Center PG","addressLine":"1 Main Rd","area":"Miyapur","city":"Hyderabad",
                 "state":"Telangana","pincode":"500049","genderPreference":"ANY","facilities":["WIFI","FOOD"],
                 "contactPhone":"9000000001","media":[{"mediaType":"IMAGE","url":"https://example.com/a.jpg"}],
                 "rooms":[{"floorNumber":1,"roomNumber":"101","sharingType":2,"ac":false,"attachedBathroom":true,
                 "dailyRent":400,"monthlyRent":7000}]}}
                """;
        JsonNode res = json(mvc.perform(auth(post("/api/support/pgs"), support).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(res.get("ownerAccountCreated").asBoolean()).isTrue();
        assertThat(res.get("pg").get("registeredVia").asText()).isEqualTo("CUSTOMER_CARE");
        assertThat(res.get("pg").get("totalBeds").asInt()).isEqualTo(2);

        mvc.perform(post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"phone-owner@test.in\",\"password\":\"" + res.get("temporaryPassword").asText()
                        + "\",\"role\":\"OWNER\"}")).andExpect(status().isOk());
    }

    private String signup(String name, String email, String role) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"phone\":\"9000012345\","
                + "\"password\":\"Secret@123\",\"role\":\"" + role + "\"}";
        return json(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String signin(String email, String role) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"Password@123\",\"role\":\"" + role + "\"}";
        return json(mvc.perform(post("/api/auth/signin").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }
}
