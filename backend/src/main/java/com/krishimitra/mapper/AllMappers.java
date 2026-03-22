package com.krishimitra.mapper;

import com.krishimitra.dto.*;
import com.krishimitra.model.entity.*;
import org.mapstruct.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * MapStruct mappers wiring all entity ↔ DTO conversions.
 * Generated at compile time — zero runtime overhead.
 *
 * All mappers are Spring beans (componentModel = "spring").
 */

// ─────────────────────────────────────────────────────────────
// MANDI PRICE MAPPER
// ─────────────────────────────────────────────────────────────
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface MandiPriceMapper {

    @Mapping(target = "mandi",          source = "mandi.name")
    @Mapping(target = "district",       source = "mandi.district")
    @Mapping(target = "state",          source = "mandi.state")
    @Mapping(target = "priceDate",      expression = "java(formatDate(entity.getPriceDate()))")
    @Mapping(target = "trendDirection", expression = "java(computeTrend(entity))")
    @Mapping(target = "changePct",      expression = "java(computeChangePct(entity))")
    LivePriceResponse toDto(MandiPrice entity);

    List<LivePriceResponse> toDtoList(List<MandiPrice> entities);

    default String formatDate(LocalDate date) {
        return date != null ? date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "";
    }

    default String computeTrend(MandiPrice p) {
        if (p.getPrevModalPrice() == null || p.getPrevModalPrice().compareTo(BigDecimal.ZERO) == 0) return "FLAT";
        int cmp = p.getModalPrice().compareTo(p.getPrevModalPrice());
        return cmp > 0 ? "UP" : cmp < 0 ? "DOWN" : "FLAT";
    }

    default double computeChangePct(MandiPrice p) {
        if (p.getPrevModalPrice() == null || p.getPrevModalPrice().compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return p.getModalPrice().subtract(p.getPrevModalPrice())
                .divide(p.getPrevModalPrice(), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }
}

// ─────────────────────────────────────────────────────────────
// PRICE PREDICTION MAPPER
// ─────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────
// SELL RECOMMENDATION MAPPER
// ─────────────────────────────────────────────────────────────
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SellRecommendationMapper {

    @Mapping(target = "sellDecision",       source = "sellDecision")
    @Mapping(target = "waitDays",           source = "waitDays")
    @Mapping(target = "currentPrice",       expression = "java(r.getCurrentPrice().doubleValue())")
    @Mapping(target = "peakPrice",          expression = "java(r.getPeakPrice() != null ? r.getPeakPrice().doubleValue() : 0.0)")
    @Mapping(target = "profitGainPerQtl",   expression = "java(r.getProfitGainPerQtl() != null ? r.getProfitGainPerQtl().doubleValue() : 0.0)")
    @Mapping(target = "totalProfitGain",    expression = "java(r.getTotalProfitGain() != null ? r.getTotalProfitGain().doubleValue() : 0.0)")
    @Mapping(target = "storageCost",        expression = "java(r.getStorageCost() != null ? r.getStorageCost().doubleValue() : 0.0)")
    @Mapping(target = "transportCost",      expression = "java(r.getTransportCost() != null ? r.getTransportCost().doubleValue() : 0.0)")
    @Mapping(target = "netGain",            expression = "java(r.getNetGain() != null ? r.getNetGain().doubleValue() : 0.0)")
    @Mapping(target = "confidence",         source = "confidenceScore")
    @Mapping(target = "reasoning",          source = "reasoning")
    SellAdviceResponse toDto(SellRecommendation r);
}

// ─────────────────────────────────────────────────────────────
// FARMER MAPPER
// ─────────────────────────────────────────────────────────────
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FarmerMapper {

    @Mapping(target = "unreadAlerts",
             expression = "java(farmer.getAlerts() == null ? 0 : (int) farmer.getAlerts().stream().filter(a -> !a.getIsRead()).count())")
    FarmerProfileResponse toDto(Farmer farmer);

    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "password",    ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    @Mapping(target = "alerts",      ignore = true)
    @Mapping(target = "crops",       ignore = true)
    void updateFarmerFromDto(UpdateProfileRequest dto, @MappingTarget Farmer farmer);
}

// ─────────────────────────────────────────────────────────────
// PREDICTION PERSISTENCE MAPPER
// (ML engine JSON response → PricePrediction entity)
// ─────────────────────────────────────────────────────────────
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PredictionPersistenceMapper {

    /**
     * Converts the raw ML engine JSON (as a Map / MlForecastResponse)
     * into a PricePrediction entity ready for db persistence.
     */
    @Mapping(target = "id",             ignore = true)
    @Mapping(target = "commodity",      source = "commodity")
    @Mapping(target = "sellDecision",   source = "sellDecision")
    @Mapping(target = "waitDays",       source = "waitDays")
    @Mapping(target = "peakDay",        source = "peakDay")
    @Mapping(target = "confidenceScore",source = "confidence")
    @Mapping(target = "horizonsJson",   expression = "java(toJson(mlResp.getHorizons()))")
    @Mapping(target = "forecastJson",   expression = "java(toJson(mlResp.getPointForecast()))")
    @Mapping(target = "lower80Json",    expression = "java(toJson(mlResp.getLower80()))")
    @Mapping(target = "upper80Json",    expression = "java(toJson(mlResp.getUpper80()))")
    @Mapping(target = "lower95Json",    expression = "java(toJson(mlResp.getLower95()))")
    @Mapping(target = "upper95Json",    expression = "java(toJson(mlResp.getUpper95()))")
    @Mapping(target = "expiresAt",      expression = "java(java.time.Instant.now().plusSeconds(1800))")
    @Mapping(target = "createdAt",      ignore = true)
    PricePrediction toEntity(MlForecastResponse mlResp);

    default String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) { return "[]"; }
    }
}
