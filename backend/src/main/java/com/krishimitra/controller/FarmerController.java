package com.krishimitra.controller;

import com.krishimitra.dto.ApiResponse;
import com.krishimitra.dto.FarmerProfileResponse;
import com.krishimitra.dto.UpdateFarmerRequest;
import com.krishimitra.security.FarmerPrincipal;
import com.krishimitra.service.FarmerService;
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
@RequestMapping("/api/v1/farmer")
@RequiredArgsConstructor
@Tag(name = "Farmer Profile", description = "Profile management, crop tracking")
@SecurityRequirement(name = "bearerAuth")
public class FarmerController {

    private final FarmerService farmerService;

    @GetMapping("/profile")
    @Operation(summary = "Get current farmer's profile with crops and alert count")
    public ResponseEntity<ApiResponse<FarmerProfileResponse>> getProfile(
            @AuthenticationPrincipal FarmerPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(farmerService.getProfile(principal.farmerId())));
    }

    @PatchMapping("/profile")
    @Operation(summary = "Update farmer profile fields")
    public ResponseEntity<ApiResponse<FarmerProfileResponse>> updateProfile(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @Valid @RequestBody UpdateFarmerRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                farmerService.updateProfile(principal.farmerId(), req)));
    }

    @PostMapping("/crops")
    @Operation(summary = "Add a crop to farmer's tracking list")
    public ResponseEntity<ApiResponse<FarmerProfileResponse.FarmerCropDto>> addCrop(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @Valid @RequestBody FarmerService.FarmerCropDto req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(farmerService.addCrop(principal.farmerId(), req)));
    }
}
