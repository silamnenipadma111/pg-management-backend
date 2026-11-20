package com.pgmanagement.service;

import com.pgmanagement.dto.CommonDtos.AddressDto;
import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeocodingServiceTest {

    private static final String REVERSE = "https://nominatim.openstreetmap.org/reverse";
    private static final String SEARCH = "https://nominatim.openstreetmap.org/search";
    private static final String INDIA_POST = "https://api.postalpincode.in/pincode/";
    private static final double LAT = 17.4486;
    private static final double LNG = 78.3908;
    private static final String HYDERABAD = "Hyderabad";
    private static final String TELANGANA = "Telangana";
    private static final String EMPTY_ARRAY = "[]";
    private static final String REVERSE_JSON = """
            {"place_id":249949923,"lat":"17.4486014","lon":"78.3908113","display_name":"Ayyappa Society Colony",
             "address":{"neighbourhood":"Ayyappa Society Colony","suburb":"HITEC City",
             "city_district":"Greater Hyderabad Municipal Corporation West Zone","city":"Hyderabad",
             "county":"Serilingampalle mandal","state_district":"Ranga Reddy","state":"Telangana",
             "postcode":"500081","country":"India","country_code":"in"}}
            """;
    private static final String PINCODE_JSON = """
            [{"lat":"12.9254325","lon":"77.6313785","name":"560034",
              "address":{"postcode":"560034","suburb":"Kormangala East","city":"Bengaluru",
              "state_district":"Bengaluru Urban","state":"Karnataka","country":"India"}}]
            """;
    private static final String INDIA_POST_JSON = """
            [{"Message":"Number of pincode(s) found:2","Status":"Success","PostOffice":[
              {"Name":"Cyberabad","District":"Hyderabad","State":"Telangana","Pincode":"500081"},
              {"Name":"Madhapur","District":"Hyderabad","State":"Telangana","Pincode":"500081"}]}]
            """;

    private MockRestServiceServer server;
    private GeocodingService service;

    @BeforeEach
    void setUp() {
        service = service(true);
    }

    @Test
    void reverseMapsTheProviderAddressAndIdentifiesTheApplication() {
        server.expect(requestTo(startsWith(REVERSE))).andExpect(queryParam("lat", "17.4486"))
                .andExpect(queryParam("lon", "78.3908")).andExpect(queryParam("format", "jsonv2"))
                .andExpect(header(HttpHeaders.USER_AGENT, "PG-Stay-Test/1.0"))
                .andRespond(withSuccess(REVERSE_JSON, MediaType.APPLICATION_JSON));

        Optional<AddressDto> address = service.reverse(LAT, LNG);

        assertThat(address).contains(new AddressDto("Ayyappa Society Colony", "HITEC City", HYDERABAD, TELANGANA,
                "500081", LAT, LNG));
        server.verify();
    }

    @Test
    void reverseBuildsTheAddressLineFromHouseNumberAndRoadAndUsesOfficialCityNames() {
        server.expect(requestTo(startsWith(REVERSE))).andRespond(withSuccess("""
                {"address":{"house_number":"12","road":"MG Road","suburb":"Indiranagar","city":"Bangalore",
                 "state":"Karnataka","postcode":"5600"}}
                """, MediaType.APPLICATION_JSON));

        AddressDto address = service.reverse(12.97, 77.59).orElseThrow();

        assertThat(address.addressLine()).isEqualTo("12 MG Road");
        assertThat(address.area()).isEqualTo("Indiranagar");
        assertThat(address.city()).isEqualTo("Bengaluru");
        assertThat(address.pincode()).as("only complete 6 digit pincodes are used").isNull();
    }

    @Test
    void hitsAndMissesAreCachedForNearbyPositions() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess(REVERSE_JSON, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess("{\"error\":\"Unable to geocode\"}", MediaType.APPLICATION_JSON));

        assertThat(service.reverse(LAT, LNG)).isPresent();
        assertThat(service.reverse(17.44861, 78.39081)).isPresent();
        assertThat(service.reverse(0.5, 0.5)).isEmpty();
        assertThat(service.reverse(0.5, 0.5)).isEmpty();
        server.verify();
    }

    @Test
    void aProviderErrorReturnsNothingAndIsRetriedNextTime() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE))).andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(startsWith(REVERSE)))
                .andRespond(withSuccess(REVERSE_JSON, MediaType.APPLICATION_JSON));

        assertThat(service.reverse(LAT, LNG)).isEmpty();
        assertThat(service.reverse(LAT, LNG)).isPresent();
        server.verify();
    }

    @Test
    void pincodeLookupReturnsCityStateAndApproximateCoordinates() {
        server.expect(requestTo(startsWith(SEARCH))).andExpect(queryParam("postalcode", "560034"))
                .andExpect(queryParam("countrycodes", "in"))
                .andRespond(withSuccess(PINCODE_JSON, MediaType.APPLICATION_JSON));

        assertThat(service.byPincode("560034")).contains(new AddressDto(null, "Kormangala East", "Bengaluru",
                "Karnataka", "560034", 12.9254325, 77.6313785));
        server.verify();
    }

    @Test
    void pincodeLookupFallsBackToIndiaPost() {
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withSuccess(EMPTY_ARRAY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(INDIA_POST + "500081"))
                .andRespond(withSuccess(INDIA_POST_JSON, MediaType.APPLICATION_JSON));

        assertThat(service.byPincode("500081")).contains(new AddressDto(null, "Cyberabad", HYDERABAD, TELANGANA,
                "500081", null, null));
        server.verify();
    }

    @Test
    void fallbackTranslatesOldCityNames() {
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withSuccess(EMPTY_ARRAY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(INDIA_POST + "560001")).andRespond(withSuccess("""
                [{"Status":"Success","PostOffice":[{"Name":"Bangalore G.P.O.","District":"Bangalore","State":"Karnataka"}]}]
                """, MediaType.APPLICATION_JSON));

        assertThat(service.byPincode("560001").orElseThrow().city()).isEqualTo("Bengaluru");
    }

    @Test
    void anUnknownPincodeIsEmptyAndCached() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(SEARCH)))
                .andRespond(withSuccess(EMPTY_ARRAY, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(INDIA_POST + "999999")).andRespond(withSuccess(
                "[{\"Status\":\"Error\",\"PostOffice\":null}]", MediaType.APPLICATION_JSON));

        assertThat(service.byPincode("999999")).isEmpty();
        assertThat(service.byPincode("999999")).isEmpty();
        server.verify();
    }

    @Test
    void unparseableCoordinatesInThePincodeAnswerAreLeftOut() {
        server.expect(requestTo(startsWith(SEARCH))).andRespond(withSuccess("""
                [{"lat":"n/a","address":{"town":"Kondapur","state":"Telangana"}}]
                """, MediaType.APPLICATION_JSON));

        AddressDto address = service.byPincode("500084").orElseThrow();

        assertThat(address.city()).isEqualTo("Kondapur");
        assertThat(address.latitude()).isNull();
        assertThat(address.longitude()).isNull();
    }

    @Test
    void aDisabledLookupNeverCallsTheProvider() {
        GeocodingService disabled = service(false);

        assertThat(disabled.reverse(LAT, LNG)).isEmpty();
        assertThat(disabled.byPincode("500081")).isEmpty();
        server.verify();
    }

    private GeocodingService service(boolean enabled) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new GeocodingService(TestProps.app(TestProps.SITE, "./uploads", TestProps.auth("false", 5, true), enabled),
                builder);
    }
}
