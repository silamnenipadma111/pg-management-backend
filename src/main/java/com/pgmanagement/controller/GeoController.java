package com.pgmanagement.controller;

import com.pgmanagement.dto.CommonDtos.AddressDto;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.service.GeocodingService;
import com.pgmanagement.validation.ValidationRules;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public address lookup used by the location pop-up and the PG registration form (rate limited per client). */
@RestController
@RequestMapping("/api/geo")
@RequiredArgsConstructor
public class GeoController {

    private static final String NOT_FOUND = "Address";

    private final GeocodingService geocodingService;

    /** "Use my current location": the address at the given coordinates. */
    @GetMapping("/reverse")
    public AddressDto reverse(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                              @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        return geocodingService.reverse(lat, lng).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }

    /** City and state of a pincode, to fill the address form automatically. */
    @GetMapping("/pincode/{pincode}")
    public AddressDto pincode(
            @PathVariable @Pattern(regexp = ValidationRules.PINCODE, message = ValidationRules.PINCODE_MESSAGE) String pincode) {
        return geocodingService.byPincode(pincode).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
    }
}
