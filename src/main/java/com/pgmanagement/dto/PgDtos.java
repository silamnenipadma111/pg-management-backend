package com.pgmanagement.dto;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.validation.ValidationRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PgDtos {

    private PgDtos() {
    }

    public record MediaDto(@NotNull Enums.MediaType mediaType, @NotBlank @Size(max = 1000) String url) {
    }

    public record BedDto(Long id, String bedLabel, Enums.BedStatus status) {
    }

    public record RoomDto(Long id, int floorNumber, String roomNumber, int sharingType, boolean ac,
                          boolean attachedBathroom, BigDecimal dailyRent, BigDecimal weeklyRent,
                          BigDecimal monthlyRent, int availableBeds, List<BedDto> beds) {
    }

    public record FloorDto(int floorNumber, List<RoomDto> rooms) {
    }

    public record PgSummaryDto(Long id, String name, String addressLine, String area, String city,
                               Enums.GenderPreference genderPreference, List<String> facilities,
                               List<String> images, BigDecimal minRent, BigDecimal maxRent,
                               List<Integer> sharingTypes, boolean hasAc, long availableBeds, long totalBeds,
                               Double distanceKm, Double latitude, Double longitude) {
    }

    /** A PG the customer has stayed at before ("book again" suggestion on the home page). */
    public record PreviousStayDto(PgSummaryDto pg, LocalDate lastStayFrom, int stays) {
    }

    public record PgDetailDto(Long id, String name, String description, String addressLine, String area,
                              String city, String state, String pincode, Double latitude, Double longitude,
                              Enums.GenderPreference genderPreference, List<String> facilities,
                              String contactPhone, String upiId, String houseRules, String ownerName,
                              Enums.RegisteredVia registeredVia, List<MediaDto> media, BigDecimal minRent,
                              BigDecimal maxRent, List<Integer> sharingTypes, long availableBeds, long totalBeds,
                              List<FloorDto> floors) {
    }

    public record BedRequest(@NotBlank @Size(max = 10) String bedLabel, Boolean available) {
    }

    public record RoomRequest(
            @Min(0) @Max(100) int floorNumber,
            @NotBlank @Size(max = 20) String roomNumber,
            @Min(1) @Max(12) int sharingType,
            boolean ac,
            boolean attachedBathroom,
            @NotNull @DecimalMin("0") BigDecimal dailyRent,
            @DecimalMin("0") BigDecimal weeklyRent,
            @NotNull @DecimalMin("1") BigDecimal monthlyRent,
            List<@Valid BedRequest> beds) {
    }

    /**
     * Latitude / longitude are optional: the website fills them from "use my current location" or from the
     * pincode lookup; they are what makes a PG appear in "PGs near you".
     */
    public record PgRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 300) String addressLine,
            @NotBlank @Size(max = 100) String area,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String state,
            @NotBlank @Pattern(regexp = ValidationRules.PINCODE, message = ValidationRules.PINCODE_MESSAGE) String pincode,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull Enums.GenderPreference genderPreference,
            @NotEmpty List<String> facilities,
            @NotBlank @Pattern(regexp = ValidationRules.PHONE, message = ValidationRules.PHONE_MESSAGE) String contactPhone,
            @Pattern(regexp = ValidationRules.UPI_ID, message = "must be a valid UPI ID like name@bank") String upiId,
            @Size(max = 2000) String houseRules,
            @NotEmpty(message = "at least one image is required") List<@Valid MediaDto> media,
            @NotEmpty(message = "at least one room is required") List<@Valid RoomRequest> rooms) {
    }

    public record BedStatusRequest(@NotNull Enums.BedStatus status) {
    }

    public record SearchCriteria(String q, String city, Integer sharing, BigDecimal minRent, BigDecimal maxRent,
                                 Enums.GenderPreference gender, Boolean ac, Double lat, Double lng,
                                 Double radiusKm, String sort) {
    }
}
