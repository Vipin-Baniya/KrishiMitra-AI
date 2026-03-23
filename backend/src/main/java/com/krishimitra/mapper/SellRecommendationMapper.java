package com.krishimitra.mapper;

import com.krishimitra.dto.SellAdviceResponse;
import com.krishimitra.model.entity.SellRecommendation;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SellRecommendationMapper {

    @Mapping(target = "sellDecision",       source = "decision")
    @Mapping(target = "waitDays",           source = "waitDays")
    @Mapping(target = "peakDay",            ignore = true)
    @Mapping(target = "currentPrice",       source = "currentPrice")
    @Mapping(target = "peakPrice",          expression = "java(java.math.BigDecimal.ZERO)")
    @Mapping(target = "profitGainPerQtl",   source = "profitGainPerQtl")
    @Mapping(target = "totalProfitGain",    expression = "java(java.math.BigDecimal.ZERO)")
    @Mapping(target = "storageCost",        source = "storageCost")
    @Mapping(target = "transportCost",      source = "transportCost")
    @Mapping(target = "netGain",            source = "netGain")
    @Mapping(target = "confidence",         ignore = true)
    @Mapping(target = "reasoning",          source = "reasoning")
    @Mapping(target = "alternativeMandis",  ignore = true)
    @Mapping(target = "explanation",        ignore = true)
    SellAdviceResponse toDto(SellRecommendation r);
}
