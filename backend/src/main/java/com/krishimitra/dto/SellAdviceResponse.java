package com.krishimitra.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
