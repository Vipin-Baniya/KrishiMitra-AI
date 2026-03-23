package com.krishimitra.mapper;

import com.krishimitra.dto.PriceForecastResponse;
import com.krishimitra.model.entity.PricePrediction;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PricePredictionMapper {

    @Mapping(target = "commodity",      source = "entity.commodity")
    @Mapping(target = "mandi",          source = "entity.mandi.name")
    @Mapping(target = "currentPrice",   source = "entity.currentPrice")
    @Mapping(target = "forecastDate",   expression = "java(entity.getForecastDate() != null ? entity.getForecastDate().toString() : \"\")")
    @Mapping(target = "horizons",       source = "entity.horizonsJson",    qualifiedByName = "jsonToIntList")
    @Mapping(target = "pointForecast",  source = "entity.forecastJson",    qualifiedByName = "jsonToDoubleList")
    @Mapping(target = "lower80",        source = "entity.lower80Json",     qualifiedByName = "jsonToDoubleList")
    @Mapping(target = "upper80",        source = "entity.upper80Json",     qualifiedByName = "jsonToDoubleList")
    @Mapping(target = "lower95",        source = "entity.lower95Json",     qualifiedByName = "jsonToDoubleList")
    @Mapping(target = "upper95",        source = "entity.upper95Json",     qualifiedByName = "jsonToDoubleList")
    @Mapping(target = "sellDecision",   source = "entity.sellDecision")
    @Mapping(target = "waitDays",       source = "entity.waitDays")
    @Mapping(target = "peakDay",        source = "entity.peakDay")
    @Mapping(target = "peakPrice",      expression = "java(entity.getPeakPrice() != null ? entity.getPeakPrice().doubleValue() : 0.0)")
    @Mapping(target = "profitGain",     expression = "java(entity.getProfitGain() != null ? entity.getProfitGain().doubleValue() : 0.0)")
    @Mapping(target = "confidence",     source = "entity.confidence")
    @Mapping(target = "fromCache",      constant = "true")
    PriceForecastResponse toDto(PricePrediction entity);

    @Named("jsonToIntList")
    static List<Integer> jsonToIntList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) { return List.of(); }
    }

    @Named("jsonToDoubleList")
    static List<Double> jsonToDoubleList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) { return List.of(); }
    }
}
