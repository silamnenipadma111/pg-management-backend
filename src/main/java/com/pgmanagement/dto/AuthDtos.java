package com.pgmanagement.dto;

import com.pgmanagement.entity.Enums;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email String email,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,15}$", message = "must be a valid phone number") String phone,
            @NotBlank @Size(min = 6, max = 64) String password,
            @NotNull Enums.UserRole role,
            Enums.Gender gender) {
    }

    public record SigninRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            Enums.UserRole role) {
    }

    public record UserDto(Long id, String name, String email, String phone, Enums.UserRole role, Enums.Gender gender) {
    }

    public record AuthResponse(String token, UserDto user) {
    }
}
