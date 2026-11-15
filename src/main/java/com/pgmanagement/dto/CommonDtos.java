package com.pgmanagement.dto;

import com.pgmanagement.entity.Enums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class CommonDtos {

    private CommonDtos() {
    }

    public record NotificationDto(Long id, String title, String message, String link, boolean read,
                                  LocalDateTime createdAt) {
    }

    public record SupportRequestCreate(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,15}$", message = "must be a valid phone number") String phone,
            @Email String email,
            @NotNull Enums.SupportRequestType requestType,
            @Size(max = 2000) String message) {
    }

    public record SupportRequestDto(Long id, String name, String phone, String email,
                                    Enums.SupportRequestType requestType, String message,
                                    Enums.SupportRequestStatus status, LocalDateTime createdAt) {
    }

    public record AssistedPgRequest(
            @NotBlank @Size(max = 100) String ownerName,
            @NotBlank @Email String ownerEmail,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,15}$", message = "must be a valid phone number") String ownerPhone,
            Long supportRequestId,
            @NotNull @Valid PgDtos.PgRequest pg) {
    }

    public record AssistedPgResponse(PgDtos.PgDetailDto pg, String ownerEmail, boolean ownerAccountCreated,
                                     String temporaryPassword) {
    }

    public record UploadResponse(String url, Enums.MediaType mediaType) {
    }
}
