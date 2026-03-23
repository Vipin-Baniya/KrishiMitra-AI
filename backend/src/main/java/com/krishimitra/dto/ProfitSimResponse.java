package com.krishimitra.dto;

import java.math.BigDecimal;
import java.util.List;

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
