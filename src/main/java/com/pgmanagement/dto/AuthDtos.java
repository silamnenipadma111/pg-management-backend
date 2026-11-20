package com.pgmanagement.dto;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.validation.StrongPassword;
import com.pgmanagement.validation.ValidationRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Pattern(regexp = ValidationRules.PERSON_NAME, message = ValidationRules.PERSON_NAME_MESSAGE) String name,
            @NotBlank @Email @Size(max = ValidationRules.EMAIL_MAX)
            @Pattern(regexp = ValidationRules.EMAIL, message = ValidationRules.EMAIL_MESSAGE) String email,
            @NotBlank @Pattern(regexp = ValidationRules.MOBILE, message = ValidationRules.MOBILE_MESSAGE) String phone,
            @NotBlank @StrongPassword String password,
            @NotNull Enums.UserRole role,
            Enums.Gender gender) {
    }

    public record SigninRequest(
            @NotBlank @Email @Size(max = ValidationRules.EMAIL_MAX) String email,
            @NotBlank @Size(max = ValidationRules.TOKEN_MAX) String password,
            Enums.UserRole role) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = ValidationRules.EMAIL_MAX) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = ValidationRules.TOKEN_MAX) String token) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = ValidationRules.TOKEN_MAX) String token,
            @NotBlank @StrongPassword String password) {
    }

    public record UserDto(Long id, String name, String email, String phone, Enums.UserRole role, Enums.Gender gender,
                          boolean emailVerified) {
    }

    /**
     * @param verificationRequired true when the account must be confirmed through the emailed link before sign-in
     */
    public record SignupResponse(String email, boolean verificationRequired, String message) {
    }

    /**
     * The signed-in user. The session itself travels in an HttpOnly cookie; {@code csrfToken} must be sent back
     * in the X-CSRF-Token header of every POST / PUT / PATCH / DELETE request.
     */
    public record SessionResponse(UserDto user, String csrfToken, LocalDateTime expiresAt) {
    }

    public record MessageResponse(String message) {
    }
}
