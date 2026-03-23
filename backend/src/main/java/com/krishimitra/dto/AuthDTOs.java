package com.krishimitra.dto;

import jakarta.validation.constraints.*;

public sealed interface AuthDTOs permits
        AuthDTOs.RegisterRequest,
        AuthDTOs.LoginRequest,
        AuthDTOs.TokenResponse,
        AuthDTOs.RefreshRequest {

    record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "Invalid Indian mobile number")
            String phone,
            @NotBlank @Size(min = 2, max = 120) String name,
            @Email String email,
            @NotBlank @Size(min = 8) String password,
            String village,
            @NotBlank String district,
            @NotBlank String state,
            Double latitude,
            Double longitude,
            String preferredLang
    ) implements AuthDTOs {}

    record LoginRequest(
            @NotBlank String phone,
            @NotBlank String password
    ) implements AuthDTOs {}

    record TokenResponse(
            String accessToken,
            String refreshToken,
            long expiresInMs,
            java.util.UUID farmerId,
            String farmerName
    ) implements AuthDTOs {}

    record RefreshRequest(
            @NotBlank String refreshToken
    ) implements AuthDTOs {}
}
