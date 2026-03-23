package com.krishimitra.controller;

import com.krishimitra.dto.ApiResponse;
import com.krishimitra.dto.AuthDTOs;
import com.krishimitra.security.FarmerPrincipal;
import com.krishimitra.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register, login, token refresh, logout")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new farmer account")
    public ResponseEntity<ApiResponse<AuthDTOs.TokenResponse>> register(
            @Valid @RequestBody AuthDTOs.RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(authService.register(req), "Account created successfully"));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with phone + password")
    public ResponseEntity<ApiResponse<AuthDTOs.TokenResponse>> login(
            @Valid @RequestBody AuthDTOs.LoginRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(req)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token using a valid refresh token")
    public ResponseEntity<ApiResponse<AuthDTOs.TokenResponse>> refresh(
            @Valid @RequestBody AuthDTOs.RefreshRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(req.refreshToken())));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke all refresh tokens for the current farmer",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal FarmerPrincipal principal) {
        authService.logout(principal.farmerId());
        return ResponseEntity.ok(ApiResponse.ok(null, "Logged out successfully"));
    }
}
