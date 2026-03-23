package com.krishimitra.controller;

import com.krishimitra.dto.ApiResponse;
import com.krishimitra.dto.LivePriceResponse;
import com.krishimitra.dto.MandiRankResponse;
import com.krishimitra.dto.PriceForecastResponse;
import com.krishimitra.service.PriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
