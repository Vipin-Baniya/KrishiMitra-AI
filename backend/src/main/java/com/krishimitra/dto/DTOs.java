package com.krishimitra.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────
// AUTH
// ─────────────────────────────────────────────────────────────

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
            UUID farmerId,
            String farmerName
    ) implements AuthDTOs {}

    record RefreshRequest(
            @NotBlank String refreshToken
    ) implements AuthDTOs {}
}

// ─────────────────────────────────────────────────────────────
// PRICE
// ─────────────────────────────────────────────────────────────

public record LivePriceResponse(
        String commodity,
        String mandi,
        String district,
        String state,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal modalPrice,
        BigDecimal arrivalsQtl,
        String priceDate,
        String trendDirection,  // UP | DOWN | FLAT
        BigDecimal changePct
) {}

public record PriceForecastResponse(
        String commodity,
        String mandi,
        BigDecimal currentPrice,
        String forecastDate,
        List<Integer> horizons,
        List<BigDecimal> pointForecast,
        List<BigDecimal> lower80,
        List<BigDecimal> upper80,
        List<BigDecimal> lower95,
        List<BigDecimal> upper95,
        String sellDecision,
        Integer waitDays,
        Integer peakDay,
        BigDecimal peakPrice,
        BigDecimal profitGain,
        Double confidence,
        Map<String, Object> explanation,
        Map<String, Double> modelWeights,
        boolean fromCache,
        long latencyMs
) {}

public record MandiRankResponse(
        String mandiName,
        String state,
        BigDecimal modalPrice,
        BigDecimal netPrice,          // after transport
        Double distanceKm,
        BigDecimal transportCostEst,
        Integer demandScore,          // 0–100 based on arrivals trend
        String recommendation
) {}

// ─────────────────────────────────────────────────────────────
// SELL ADVICE
// ─────────────────────────────────────────────────────────────

public record SellAdviceRequest(
        @NotBlank String commodity,
        @NotBlank String mandi,
        @Positive double quantityQuintal,
        boolean storageAvailable
) {}

public record SellAdviceResponse(
        String sellDecision,
        Integer waitDays,
        Integer peakDay,
        BigDecimal currentPrice,
        BigDecimal peakPrice,
        BigDecimal profitGainPerQtl,
        BigDecimal totalProfitGain,
        BigDecimal storageCost,
        BigDecimal transportCost,
        BigDecimal netGain,
        Double confidence,
        String reasoning,
        List<MandiRankResponse> alternativeMandis,
        Map<String, Object> explanation
) {}

// ─────────────────────────────────────────────────────────────
// PROFIT SIMULATION
// ─────────────────────────────────────────────────────────────

public record ProfitSimRequest(
        @NotBlank String commodity,
        @NotBlank String mandi,
        @Positive @Max(1000) double quantityQuintal,
        @Min(0) @Max(30) int waitDays
) {}

public record ProfitSimResponse(
        String commodity,
        String mandi,
        Double quantityQuintal,
        Integer waitDays,
        BigDecimal currentPrice,
        BigDecimal predictedPrice,
        BigDecimal storageCost,
        BigDecimal transportCost,
        BigDecimal grossRevenue,
        BigDecimal netRevenue,
        BigDecimal profitVsNow,
        List<ScenarioPoint> scenarioChart   // all horizons for chart
) {
    public record ScenarioPoint(int days, BigDecimal profit) {}
}

// ─────────────────────────────────────────────────────────────
// CROP RECOMMENDATION
// ─────────────────────────────────────────────────────────────

public record CropRecommendationRequest(
        @NotBlank String location,
        String soilType,
        String season,
        String waterSource,
        @Positive double acres
) {}

public record CropRecommendationResponse(
        String location,
        String season,
        List<CropSuggestion> recommendations
) {
    public record CropSuggestion(
            String crop,
            String icon,
            int matchScore,
            String profitRange,
            String riskLevel,
            int growthDays,
            String bestMandi,
            String reason
    ) {}
}

// ─────────────────────────────────────────────────────────────
// AI CHAT
// ─────────────────────────────────────────────────────────────

public record ChatRequest(
        UUID sessionId,     // null = create new session
        @NotBlank @Size(max = 2000) String message,
        String language,
        ChatContext context
) {
    public record ChatContext(
            String location,
            List<String> crops,
            Map<String, Object> livePrices,
            String weatherBrief
    ) {}
}

public record ChatResponse(
        UUID sessionId,
        String content,
        String modelUsed,
        Double confidence,
        Long latencyMs,
        Instant createdAt
) {}

// ─────────────────────────────────────────────────────────────
// FARMER PROFILE
// ─────────────────────────────────────────────────────────────

public record FarmerProfileResponse(
        UUID id,
        String name,
        String phone,
        String email,
        String village,
        String district,
        String state,
        String preferredLang,
        List<FarmerCropDto> crops,
        int unreadAlerts
) {
    public record FarmerCropDto(
            UUID id,
            String commodity,
            String variety,
            BigDecimal quantityQuintal,
            String expectedHarvest,
            boolean storageAvailable
    ) {}
}

public record UpdateFarmerRequest(
        @Size(min = 2, max = 120) String name,
        @Email String email,
        String village,
        String district,
        String state,
        Double latitude,
        Double longitude,
        String preferredLang
) {}

// ─────────────────────────────────────────────────────────────
// ALERT
// ─────────────────────────────────────────────────────────────

public record AlertResponse(
        UUID id,
        String type,
        String severity,
        String commodity,
        String mandiName,
        String title,
        String body,
        boolean isRead,
        String expiresAt,
        String createdAt
) {}

public record AlertPageResponse(
        List<AlertResponse> alerts,
        long totalUnread,
        int page,
        int totalPages
) {}

// ─────────────────────────────────────────────────────────────
// COMMON
// ─────────────────────────────────────────────────────────────

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now());
    }
// ─────────────────────────────────────────────────────────────
// CHAT SESSION RESPONSE  (Fix #3)
// ─────────────────────────────────────────────────────────────
@lombok.Builder
public record ChatSessionResponse(
        String id,
        String lastMessage,
        String createdAt
) {}

// ─────────────────────────────────────────────────────────────
// UPDATE PROFILE REQUEST  (Fix #8)
// ─────────────────────────────────────────────────────────────
public record UpdateProfileRequest(
        @jakarta.validation.constraints.Size(min = 2, max = 120) String name,
        String email,
        String village,
        String district,
        String state,
        Double latitude,
        Double longitude,
        @jakarta.validation.constraints.Pattern(regexp = "^(en|hi|mr|gu|pa)$") String preferredLang
) {}

// ─────────────────────────────────────────────────────────────
// ML FORECAST RESPONSE  (Fix #9) — matches Python ML engine JSON
// ─────────────────────────────────────────────────────────────
@lombok.Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record MlForecastResponse(
        String commodity,
        String mandi,
        @com.fasterxml.jackson.annotation.JsonProperty("current_price")  Double currentPrice,
        @com.fasterxml.jackson.annotation.JsonProperty("sell_decision")  String sellDecision,
        @com.fasterxml.jackson.annotation.JsonProperty("wait_days")      Integer waitDays,
        @com.fasterxml.jackson.annotation.JsonProperty("peak_day")       Integer peakDay,
        @com.fasterxml.jackson.annotation.JsonProperty("peak_price")     Double peakPrice,
        @com.fasterxml.jackson.annotation.JsonProperty("profit_gain")    Double profitGain,
        Double confidence,
        java.util.List<Integer> horizons,
        @com.fasterxml.jackson.annotation.JsonProperty("point_forecast") java.util.List<Double> pointForecast,
        @com.fasterxml.jackson.annotation.JsonProperty("lower_80")       java.util.List<Double> lower80,
        @com.fasterxml.jackson.annotation.JsonProperty("upper_80")       java.util.List<Double> upper80,
        @com.fasterxml.jackson.annotation.JsonProperty("lower_95")       java.util.List<Double> lower95,
        @com.fasterxml.jackson.annotation.JsonProperty("upper_95")       java.util.List<Double> upper95,
        @com.fasterxml.jackson.annotation.JsonProperty("from_cache")     Boolean fromCache,
        @com.fasterxml.jackson.annotation.JsonProperty("latency_ms")     Long latencyMs
) {}


}

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {}

public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> fieldErrors
) {}
