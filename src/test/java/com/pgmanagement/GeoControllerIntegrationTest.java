package com.pgmanagement;

import com.pgmanagement.dto.CommonDtos.AddressDto;
import com.pgmanagement.service.GeocodingService;
import com.pgmanagement.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public address lookup endpoints; the provider itself is replaced by a mock. */
@ApiIntegrationTest
class GeoControllerIntegrationTest {

    private static final String REVERSE_URL = "/api/geo/reverse";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private GeocodingService geocodingService;

    @Test
    void reverseLookupReturnsTheAddressWithoutSigningIn() throws Exception {
        when(geocodingService.reverse(17.4486, 78.3908)).thenReturn(Optional.of(
                new AddressDto("Ayyappa Society", "Madhapur", "Hyderabad", "Telangana", "500081", 17.4486, 78.3908)));

        mvc.perform(get(REVERSE_URL).param("lat", "17.4486").param("lng", "78.3908")).andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Hyderabad")).andExpect(jsonPath("$.pincode").value("500081"))
                .andExpect(jsonPath("$.addressLine").value("Ayyappa Society"));
    }

    @Test
    void pincodeLookupReturnsCityAndState() throws Exception {
        when(geocodingService.byPincode("560034")).thenReturn(Optional.of(
                new AddressDto(null, "Koramangala", "Bengaluru", "Karnataka", "560034", 12.92, 77.63)));

        mvc.perform(get("/api/geo/pincode/560034")).andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Bengaluru")).andExpect(jsonPath("$.state").value("Karnataka"));
    }

    @Test
    void unknownPlacesAreNotFound() throws Exception {
        when(geocodingService.reverse(1.0, 1.0)).thenReturn(Optional.empty());
        when(geocodingService.byPincode("999999")).thenReturn(Optional.empty());

        mvc.perform(get(REVERSE_URL).param("lat", "1").param("lng", "1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Address not found"));
        mvc.perform(get("/api/geo/pincode/999999")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/geo/reverse?lat=95&lng=78", "/api/geo/reverse?lat=17&lng=181",
            "/api/geo/reverse?lat=abc&lng=78", "/api/geo/reverse?lat=17", "/api/geo/pincode/50008",
            "/api/geo/pincode/abcdef"})
    void invalidInputIsRejectedBeforeTheProviderIsCalled(String url) throws Exception {
        mvc.perform(get(url)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(geocodingService);
    }
}
