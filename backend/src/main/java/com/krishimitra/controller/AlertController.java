package com.krishimitra.controller;

import com.krishimitra.dto.AlertPageResponse;
import com.krishimitra.dto.ApiResponse;
import com.krishimitra.security.FarmerPrincipal;
import com.krishimitra.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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
