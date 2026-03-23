package com.krishimitra.dto;

import java.math.BigDecimal;

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
