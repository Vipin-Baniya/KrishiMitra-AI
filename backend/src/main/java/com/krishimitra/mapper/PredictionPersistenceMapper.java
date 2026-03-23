package com.krishimitra.mapper;

import com.krishimitra.dto.MlForecastResponse;
import com.krishimitra.model.entity.PricePrediction;
import org.mapstruct.*;

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
    @Mapping(target = "confidence",     expression = "java(mlResp.confidence() != null ? java.math.BigDecimal.valueOf(mlResp.confidence()) : null)")
    @Mapping(target = "pointForecast",  expression = "java(toJson(mlResp.pointForecast()))")
    @Mapping(target = "lower80",        expression = "java(toJson(mlResp.lower80()))")
    @Mapping(target = "upper80",        expression = "java(toJson(mlResp.upper80()))")
    @Mapping(target = "lower95",        expression = "java(toJson(mlResp.lower95()))")
    @Mapping(target = "upper95",        expression = "java(toJson(mlResp.upper95()))")
    @Mapping(target = "expiresAt",      expression = "java(java.time.Instant.now().plusSeconds(1800))")
    @Mapping(target = "generatedAt",    ignore = true)
    @Mapping(target = "mandi",          ignore = true)
    @Mapping(target = "currentPrice",   ignore = true)
    @Mapping(target = "peakPrice",      ignore = true)
    @Mapping(target = "profitGain",     ignore = true)
    @Mapping(target = "modelWeights",   ignore = true)
    @Mapping(target = "explanation",    ignore = true)
    PricePrediction toEntity(MlForecastResponse mlResp);

    default String toJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) { return "[]"; }
    }
}
