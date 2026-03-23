package com.krishimitra.controller;

import com.krishimitra.dto.ApiResponse;
import com.krishimitra.dto.ProfitSimRequest;
import com.krishimitra.dto.ProfitSimResponse;
import com.krishimitra.dto.SellAdviceRequest;
import com.krishimitra.dto.SellAdviceResponse;
import com.krishimitra.security.FarmerPrincipal;
import com.krishimitra.service.SellAdvisorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sell")
@RequiredArgsConstructor
@Tag(name = "Sell Advisor", description = "SELL/WAIT decision, profit simulation, mandi comparison")
@SecurityRequirement(name = "bearerAuth")
public class SellAdvisorController {

    private final SellAdvisorService sellAdvisorService;

    @PostMapping("/advice")
    @Operation(summary = "Get SELL_NOW / WAIT_N_DAYS / HOLD recommendation",
               description = "Core decision engine: combines ML forecast + storage cost + transport.")
    public ResponseEntity<ApiResponse<SellAdviceResponse>> getAdvice(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @Valid @RequestBody SellAdviceRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                sellAdvisorService.getAdvice(principal.farmerId(), req)));
    }

    @PostMapping("/simulate")
    @Operation(summary = "Profit simulation — what if I wait N days?",
               description = "Returns gross revenue, storage cost, net gain vs selling today for all horizons.")
    public ResponseEntity<ApiResponse<ProfitSimResponse>> simulate(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @Valid @RequestBody ProfitSimRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                sellAdvisorService.simulate(principal.farmerId(), req)));
    }
}
