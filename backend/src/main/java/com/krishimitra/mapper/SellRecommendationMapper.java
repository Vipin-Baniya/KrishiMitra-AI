package com.krishimitra.mapper;

import com.krishimitra.dto.SellAdviceResponse;
import com.krishimitra.model.entity.SellRecommendation;
import org.mapstruct.*;

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
