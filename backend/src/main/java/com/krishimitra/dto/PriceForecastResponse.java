package com.krishimitra.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
