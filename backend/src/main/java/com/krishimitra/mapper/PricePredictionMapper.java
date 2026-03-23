package com.krishimitra.mapper;

import com.krishimitra.dto.PriceForecastResponse;
import com.krishimitra.model.entity.PricePrediction;
import org.mapstruct.*;

import java.math.BigDecimal;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PricePredictionMapper {

    @Mapping(target = "commodity",      source = "entity.commodity")
    @Mapping(target = "mandi",          source = "entity.mandi.name")
    @Mapping(target = "currentPrice",   source = "entity.currentPrice")
    @Mapping(target = "forecastDate",   expression = "java(entity.getGeneratedAt() != null ? entity.getGeneratedAt().toString() : \"\")")
    @Mapping(target = "horizons",       ignore = true)
    @Mapping(target = "pointForecast",  source = "entity.pointForecast",   qualifiedByName = "jsonToBigDecimalList")
    @Mapping(target = "lower80",        source = "entity.lower80",         qualifiedByName = "jsonToBigDecimalList")
    @Mapping(target = "upper80",        source = "entity.upper80",         qualifiedByName = "jsonToBigDecimalList")
    @Mapping(target = "lower95",        source = "entity.lower95",         qualifiedByName = "jsonToBigDecimalList")
    @Mapping(target = "upper95",        source = "entity.upper95",         qualifiedByName = "jsonToBigDecimalList")
    @Mapping(target = "sellDecision",   source = "entity.sellDecision")
    @Mapping(target = "waitDays",       source = "entity.waitDays")
    @Mapping(target = "peakDay",        source = "entity.peakDay")
    @Mapping(target = "peakPrice",      expression = "java(entity.getPeakPrice() != null ? entity.getPeakPrice() : java.math.BigDecimal.ZERO)")
    @Mapping(target = "profitGain",     expression = "java(entity.getProfitGain() != null ? entity.getProfitGain() : java.math.BigDecimal.ZERO)")
    @Mapping(target = "confidence",     source = "entity.confidence")
    @Mapping(target = "fromCache",      constant = "true")
    @Mapping(target = "explanation",    ignore = true)
    @Mapping(target = "modelWeights",   ignore = true)
    @Mapping(target = "latencyMs",      ignore = true)
    PriceForecastResponse toDto(PricePrediction entity);

    @Named("jsonToBigDecimalList")
    static List<BigDecimal> jsonToBigDecimalList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Double> doubles = om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return doubles.stream().map(BigDecimal::valueOf).toList();
        } catch (Exception e) { return List.of(); }
    }
}
