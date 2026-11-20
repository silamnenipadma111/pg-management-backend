package com.pgmanagement;

import com.pgmanagement.support.ApiClient;
import com.pgmanagement.support.ApiClient.Session;
import com.pgmanagement.support.ApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static com.pgmanagement.support.ApiClient.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end booking and rent payment journey of a customer and a PG owner against the seeded demo data. */
@ApiIntegrationTest
class BookingFlowIntegrationTest {

    private static final String OWNER_EMAIL = "owner@pgstay.in";
    private static final String STATUS = "status";
    private static final String BED_STATUS = "bedStatus";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, objectMapper);
    }

    @Test
    void nearbySearchSortsByDistanceAndFiltersByRadius() throws Exception {
        JsonNode list = api.getJson(get("/api/pgs").param("lat", "17.4486").param("lng", "78.3908")
                .param("radiusKm", "10"));
        assertThat(list.size()).isPositive();
        assertThat(list.get(0).get("area").asString()).isEqualTo("Madhapur");
        list.forEach(p -> assertThat(p.get("distanceKm").asDouble()).isLessThanOrEqualTo(10));

        JsonNode female = api.getJson(get("/api/pgs").param("gender", "FEMALE").param("sharing", "2"));
        assertThat(female.size()).isPositive();
        female.forEach(p -> assertThat(p.get("genderPreference").asString()).isEqualTo("FEMALE"));
    }

    @Test
    void searchSupportsTextCityAndPriceSorting() throws Exception {
        JsonNode byCity = api.getJson(get("/api/pgs").param("city", "Bengaluru").param("sort", "PRICE_ASC"));
        assertThat(byCity.size()).isPositive();
        double previous = 0;
        for (JsonNode pg : byCity) {
            assertThat(pg.get("city").asString()).isEqualTo("Bengaluru");
            assertThat(pg.get("minRent").asDouble()).isGreaterThanOrEqualTo(previous);
            previous = pg.get("minRent").asDouble();
        }
        assertThat(api.getJson(get("/api/pgs").param("q", "no-such-place-anywhere")).size()).isZero();
        assertThat(api.getJson(get("/api/meta/options")).get("facilities").size()).isPositive();
    }

    @Test
    void fullBookingLifecycleBlocksThenBooksBedAndIssuesInvoice() throws Exception {
        Session customer = api.signupAndSignin("Test Customer", "flow-customer@test.in", "CUSTOMER");
        Session owner = api.signin(OWNER_EMAIL, ApiClient.DEMO_PASSWORD, "OWNER");

        long pgId = api.getJson(get("/api/pgs").param("q", "Sri Sai")).get(0).get("id").asLong();
        long bedId = firstAvailableBed(pgId);

        String body = "{\"bedId\":" + bedId + ",\"bookingType\":\"YEARLY\",\"paymentPlan\":\"MONTHLY\",\"duration\":1,"
                + "\"startDate\":\"" + LocalDate.now().plusDays(1) + "\"}";
        JsonNode booking = api.json(api.perform(as(customer, post("/api/bookings")).content(body))
                .andExpect(status().isCreated()));
        assertThat(booking.get(STATUS).asString()).isEqualTo("REQUESTED");
        assertThat(booking.get(BED_STATUS).asString()).isEqualTo("BLOCKED");
        // dates are ISO strings, which is what the website parses
        assertThat(booking.get("startDate").asString()).isEqualTo(LocalDate.now().plusDays(1).toString());

        // second request on the same bed must fail while it is blocked
        api.perform(as(customer, post("/api/bookings")).content(body)).andExpect(status().isConflict());

        long bookingId = booking.get("id").asLong();
        JsonNode approved = api.json(api.perform(as(owner, post("/api/owner/bookings/" + bookingId + "/approve")))
                .andExpect(status().isOk()));
        assertThat(approved.get("totalInstallments").asInt()).isEqualTo(12);

        // upcoming shows only the next installment of the booking
        JsonNode upcoming = api.getJson(as(customer, get("/api/payments/my")));
        assertThat(upcoming.size()).isEqualTo(1);
        assertThat(upcoming.get(0).get("installmentNo").asInt()).isEqualTo(1);
        long paymentId = upcoming.get(0).get("id").asLong();

        // cannot pay the 2nd installment before the first
        long second = installment(owner, pgId, bookingId, 2);
        api.perform(as(customer, post("/api/payments/" + second + "/checkout")).content("{\"paymentMethod\":\"UPI\"}"))
                .andExpect(status().isBadRequest());

        // UPI: checkout returns a upi:// deep link, completing marks the rent PAID without owner action
        JsonNode order = api.json(api.perform(as(customer, post("/api/payments/" + paymentId + "/checkout"))
                .content("{\"paymentMethod\":\"UPI\"}")).andExpect(status().isOk()));
        assertThat(order.get("upiUri").asString()).startsWith("upi://pay?pa=ravikumar.demo%40pgstay")
                .contains("&am=").contains("&cu=INR");
        api.perform(as(customer, post("/api/payments/" + paymentId + "/complete"))
                        .content("{\"orderId\":\"WRONG\",\"paymentMethod\":\"UPI\",\"upiApp\":\"GPAY\"}"))
                .andExpect(status().isBadRequest());
        JsonNode confirmed = api.json(api.perform(as(customer, post("/api/payments/" + paymentId + "/complete"))
                        .content("{\"orderId\":\"" + order.get("orderId").asString()
                                + "\",\"paymentMethod\":\"UPI\",\"upiApp\":\"GPAY\",\"payerUpiId\":\"flow@okaxis\"}"))
                .andExpect(status().isOk()));
        assertThat(confirmed.get(STATUS).asString()).isEqualTo("PAID");
        assertThat(confirmed.get("bookingStatus").asString()).isEqualTo("BOOKED");
        assertThat(confirmed.get("paymentDetails").asString()).isEqualTo("Google Pay - flow@okaxis");

        // next installment is now the only upcoming one; pay it in cash -> owner must accept
        upcoming = api.getJson(as(customer, get("/api/payments/my")));
        assertThat(upcoming.size()).isEqualTo(1);
        assertThat(upcoming.get(0).get("installmentNo").asInt()).isEqualTo(2);
        JsonNode cash = api.json(api.perform(as(customer, post("/api/payments/" + second + "/cash"))
                        .content("{\"paidOn\":\"" + LocalDate.now() + "\",\"receivedBy\":\"Ravi Kumar\"}"))
                .andExpect(status().isOk()));
        assertThat(cash.get(STATUS).asString()).isEqualTo("SUBMITTED");
        JsonNode accepted = api.json(api.perform(as(owner, post("/api/owner/payments/" + second + "/confirm")))
                .andExpect(status().isOk()));
        assertThat(accepted.get(STATUS).asString()).isEqualTo("PAID");
        assertThat(accepted.get("paymentMethod").asString()).isEqualTo("CASH");

        // owner can send a due reminder for the 3rd installment
        long third = api.getJson(as(customer, get("/api/payments/my"))).get(0).get("id").asLong();
        JsonNode reminder = api.json(api.perform(as(owner, post("/api/owner/payments/" + third + "/remind")))
                .andExpect(status().isOk()));
        assertThat(reminder.get("channels").toString()).contains("WEBSITE");

        JsonNode invoice = api.getJson(as(customer, get("/api/payments/" + paymentId + "/invoice")));
        assertThat(invoice.get("invoiceNumber").asString()).startsWith("INV-");
        // the owner of the PG may open the same invoice, other customers may not
        api.perform(as(owner, get("/api/payments/" + paymentId + "/invoice"))).andExpect(status().isOk());
        Session stranger = api.signin("customer2@pgstay.in", ApiClient.DEMO_PASSWORD, "CUSTOMER");
        api.perform(as(stranger, get("/api/payments/" + paymentId + "/invoice"))).andExpect(status().isNotFound());

        JsonNode myBookings = api.getJson(as(customer, get("/api/bookings/my")));
        assertThat(myBookings.get(0).get(BED_STATUS).asString()).isEqualTo("BOOKED");
        assertThat(api.getJson(as(customer, get("/api/payments/my").param("scope", "history"))).size()).isEqualTo(2);

        JsonNode notifications = api.getJson(as(owner, get("/api/notifications")));
        assertThat(notifications.get("items").toString()).contains("flow-customer@test.in");
        assertThat(notifications.get("unread").asInt()).isPositive();
        api.perform(as(owner, post("/api/notifications/read-all"))).andExpect(status().isOk());
        assertThat(api.getJson(as(owner, get("/api/notifications"))).get("unread").asInt()).isZero();

        // a booked stay becomes a "book again" suggestion on the customer's home page
        JsonNode suggestions = api.getJson(as(customer, get("/api/bookings/suggestions")));
        assertThat(suggestions.size()).isEqualTo(1);
        assertThat(suggestions.get(0).get("pg").get("id").asLong()).isEqualTo(pgId);
        assertThat(suggestions.get(0).get("stays").asInt()).isEqualTo(1);
    }

    @Test
    void customerCanCancelAndOwnerCanRejectWhichFreesTheBed() throws Exception {
        Session customer = api.signupAndSignin("Cancel Customer", "cancel-customer@test.in", "CUSTOMER");
        Session owner = api.signin(OWNER_EMAIL, ApiClient.DEMO_PASSWORD, "OWNER");
        long pgId = api.getJson(get("/api/pgs").param("q", "Green Nest")).get(0).get("id").asLong();
        long bedId = firstAvailableBed(pgId);
        String body = "{\"bedId\":" + bedId + ",\"bookingType\":\"WEEKLY\",\"duration\":2,\"startDate\":\""
                + LocalDate.now().plusDays(2) + "\"}";

        long first = api.json(api.perform(as(customer, post("/api/bookings")).content(body))
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode cancelled = api.json(api.perform(as(customer, post("/api/bookings/" + first + "/cancel")))
                .andExpect(status().isOk()));
        assertThat(cancelled.get(STATUS).asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get(BED_STATUS).asString()).isEqualTo("AVAILABLE");
        api.perform(as(customer, post("/api/bookings/" + first + "/cancel"))).andExpect(status().isBadRequest());

        long second = api.json(api.perform(as(customer, post("/api/bookings")).content(body))
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode rejected = api.json(api.perform(as(owner, post("/api/owner/bookings/" + second + "/reject"))
                .content("{\"note\":\"Room under repair\"}")).andExpect(status().isOk()));
        assertThat(rejected.get(STATUS).asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("ownerNote").asString()).isEqualTo("Room under repair");
        assertThat(rejected.get(BED_STATUS).asString()).isEqualTo("AVAILABLE");

        // an out of range duration is refused before anything is blocked
        api.perform(as(customer, post("/api/bookings")).content(body.replace("\"duration\":2", "\"duration\":60")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerManagesBedsAndSeesDashboardTotals() throws Exception {
        Session owner = api.signin(OWNER_EMAIL, ApiClient.DEMO_PASSWORD, "OWNER");
        JsonNode pgs = api.getJson(as(owner, get("/api/owner/pgs")));
        assertThat(pgs.size()).isPositive();
        long pgId = pgs.get(0).get("id").asLong();
        long bedId = firstAvailableBed(pgId);

        JsonNode dashboard = api.getJson(as(owner, get("/api/owner/dashboard")));
        long availableBefore = dashboard.get("totals").get("availableBeds").asLong();
        assertThat(dashboard.get("pgs").size()).isEqualTo(pgs.size());

        api.perform(as(owner, patch("/api/owner/beds/" + bedId + "/status")).content("{\"status\":\"UNAVAILABLE\"}"))
                .andExpect(status().isOk());
        assertThat(api.getJson(as(owner, get("/api/owner/dashboard"))).get("totals").get("availableBeds").asLong())
                .isEqualTo(availableBefore - 1);
        // owners cannot set the statuses that only a booking produces
        api.perform(as(owner, patch("/api/owner/beds/" + bedId + "/status")).content("{\"status\":\"BOOKED\"}"))
                .andExpect(status().isBadRequest());
        // another owner's PG is invisible
        Session otherOwner = api.signin("owner2@pgstay.in", ApiClient.DEMO_PASSWORD, "OWNER");
        api.perform(as(otherOwner, get("/api/owner/pgs/" + pgId))).andExpect(status().isNotFound());
    }

    @Test
    void customerCareRegistersPgForNewOwner() throws Exception {
        Session support = api.signin("support@pgstay.in", ApiClient.DEMO_PASSWORD, "SUPPORT");
        String body = """
                {"ownerName":"Phone Owner","ownerEmail":"phone-owner@test.in","ownerPhone":"9000000001",
                 "pg":{"name":"Call Center PG","addressLine":"1 Main Rd","area":"Miyapur","city":"Hyderabad",
                 "state":"Telangana","pincode":"500049","genderPreference":"ANY","facilities":["WIFI","FOOD"],
                 "contactPhone":"9000000001","media":[{"mediaType":"IMAGE","url":"https://example.com/a.jpg"}],
                 "rooms":[{"floorNumber":1,"roomNumber":"101","sharingType":2,"ac":false,"attachedBathroom":true,
                 "dailyRent":400,"monthlyRent":7000}]}}
                """;
        JsonNode res = api.json(api.perform(as(support, post("/api/support/pgs")).content(body))
                .andExpect(status().isCreated()));
        assertThat(res.get("ownerAccountCreated").asBoolean()).isTrue();
        assertThat(res.get("pg").get("registeredVia").asString()).isEqualTo("CUSTOMER_CARE");
        assertThat(res.get("pg").get("totalBeds").asInt()).isEqualTo(2);

        Session newOwner = api.signin("phone-owner@test.in", res.get("temporaryPassword").asString(), "OWNER");
        assertThat(api.getJson(as(newOwner, get("/api/owner/pgs"))).size()).isEqualTo(1);
    }

    private long firstAvailableBed(long pgId) throws Exception {
        JsonNode detail = api.getJson(get("/api/pgs/" + pgId));
        for (JsonNode floor : detail.get("floors")) {
            for (JsonNode room : floor.get("rooms")) {
                for (JsonNode bed : room.get("beds")) {
                    if ("AVAILABLE".equals(bed.get(STATUS).asString())) {
                        return bed.get("id").asLong();
                    }
                }
            }
        }
        throw new AssertionError("No available bed in PG " + pgId);
    }

    private long installment(Session owner, long pgId, long bookingId, int number) throws Exception {
        JsonNode payments = api.getJson(as(owner, get("/api/owner/payments").param("pgId", String.valueOf(pgId))));
        for (JsonNode payment : payments) {
            if (payment.get("bookingId").asLong() == bookingId && payment.get("installmentNo").asInt() == number) {
                return payment.get("id").asLong();
            }
        }
        throw new AssertionError("Installment " + number + " of booking " + bookingId + " not found");
    }
}
