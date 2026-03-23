package com.krishimitra.controller;

import com.krishimitra.dto.ApiResponse;
import com.krishimitra.dto.CropRecommendationRequest;
import com.krishimitra.dto.CropRecommendationResponse;
import com.krishimitra.security.FarmerPrincipal;
import com.krishimitra.service.CropAdvisorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/crops")
@RequiredArgsConstructor
@Tag(name = "Crop Advisor", description = "AI-powered crop recommendations based on soil, season, location")
@SecurityRequirement(name = "bearerAuth")
public class CropAdvisorController {

    private final CropAdvisorService cropAdvisorService;

    @PostMapping("/recommend")
    @Operation(summary = "Get crop recommendations for the farmer's location and farm profile")
    public ResponseEntity<ApiResponse<CropRecommendationResponse>> recommend(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @Valid @RequestBody CropRecommendationRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                cropAdvisorService.recommend(principal.farmerId(), req)));
    }
}
