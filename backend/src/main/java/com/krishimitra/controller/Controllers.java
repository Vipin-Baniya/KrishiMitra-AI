package com.krishimitra.controller;

import com.krishimitra.dto.*;
import com.krishimitra.security.FarmerPrincipal;
import com.krishimitra.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────
// AUTH CONTROLLER
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// PRICE CONTROLLER
// ─────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/prices")
@RequiredArgsConstructor
@Tag(name = "Prices", description = "Live mandi prices, forecasts, mandi ranking")
public class PriceController {

    private final PriceService priceService;

    @GetMapping("/live")
    @Operation(summary = "Get live mandi prices",
               description = "Returns latest modal prices from Agmarknet. No auth required.")
    public ResponseEntity<ApiResponse<List<LivePriceResponse>>> getLivePrices(
            @RequestParam String commodity,
            @RequestParam(required = false) String state) {
        return ResponseEntity.ok(ApiResponse.ok(priceService.getLivePrices(commodity, state)));
    }

    @GetMapping("/forecast")
    @Operation(summary = "Get 30-day price forecast for a commodity at a mandi",
               description = "Returns ARIMA+LSTM+XGBoost ensemble prediction with confidence intervals.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ApiResponse<PriceForecastResponse>> getForecast(
            @RequestParam String commodity,
            @RequestParam String mandi) {
        return ResponseEntity.ok(ApiResponse.ok(priceService.getForecast(commodity, mandi)));
    }

    @GetMapping("/mandis/rank")
    @Operation(summary = "Rank nearby mandis by net profit for a commodity",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ApiResponse<List<MandiRankResponse>>> rankMandis(
            @RequestParam String commodity,
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5") int topN) {
        return ResponseEntity.ok(ApiResponse.ok(priceService.rankMandis(commodity, lat, lng, topN)));
    }
}

// ─────────────────────────────────────────────────────────────
// SELL ADVISOR CONTROLLER
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// FARMER CONTROLLER
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// ALERT CONTROLLER
// ─────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@Tag(name = "Alerts", description = "Smart alerts — price spikes, sell windows, weather warnings")
@SecurityRequirement(name = "bearerAuth")
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    @Operation(summary = "Get paginated alerts for the current farmer")
    public ResponseEntity<ApiResponse<AlertPageResponse>> getAlerts(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                alertService.getAlerts(principal.farmerId(), page, size)));
    }

    @PutMapping("/read-all")
    @Operation(summary = "Mark all alerts as read")
    public ResponseEntity<ApiResponse<Void>> markAllRead(
            @AuthenticationPrincipal FarmerPrincipal principal) {
        alertService.markAllRead(principal.farmerId());
        return ResponseEntity.ok(ApiResponse.ok(null, "All alerts marked as read"));
    }

    @PutMapping("/{alertId}/read")
    @Operation(summary = "Mark a specific alert as read")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @PathVariable UUID alertId) {
        alertService.markRead(principal.farmerId(), alertId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}

// ─────────────────────────────────────────────────────────────
// AI CHAT CONTROLLER
// ─────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "Conversational KrishiMitra AI — routes to custom LLM → OpenAI → Claude")
@SecurityRequirement(name = "bearerAuth")
public class AiChatController {

    private final AiChatService chatService;

    @PostMapping("/chat")
    @Operation(summary = "Chat with KrishiMitra AI",
               description = "Sends a message to the LLM with farmer context injected. "
                           + "Routes: custom model → OpenAI GPT-4o → Claude (fallback).")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
            @AuthenticationPrincipal FarmerPrincipal principal,
            @Valid @RequestBody ChatRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                chatService.sendMessage(principal.farmerId(), req)));
    }

    @GetMapping("/sessions")
    @Operation(summary = "List the farmer's recent chat sessions")
    public ResponseEntity<ApiResponse<List<ChatSessionDto>>> getSessions(
            @AuthenticationPrincipal FarmerPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(
                chatService.getSessions(principal.farmerId())));
    }

    public record ChatSessionDto(UUID id, String lastMessage, String createdAt) {}
}

// ─────────────────────────────────────────────────────────────
// CROP ADVISOR CONTROLLER
// ─────────────────────────────────────────────────────────────

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
