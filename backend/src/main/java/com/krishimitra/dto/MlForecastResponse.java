package com.krishimitra.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlForecastResponse(
        String commodity,
        String mandi,
        @JsonProperty("current_price")  Double currentPrice,
        @JsonProperty("sell_decision")  String sellDecision,
        @JsonProperty("wait_days")      Integer waitDays,
        @JsonProperty("peak_day")       Integer peakDay,
        @JsonProperty("peak_price")     Double peakPrice,
        @JsonProperty("profit_gain")    Double profitGain,
        Double confidence,
        List<Integer> horizons,
        @JsonProperty("point_forecast") List<Double> pointForecast,
        @JsonProperty("lower_80")       List<Double> lower80,
        @JsonProperty("upper_80")       List<Double> upper80,
        @JsonProperty("lower_95")       List<Double> lower95,
        @JsonProperty("upper_95")       List<Double> upper95,
        @JsonProperty("from_cache")     Boolean fromCache,
        @JsonProperty("latency_ms")     Long latencyMs
) {}
