package com.krishimitra.mapper;

import com.krishimitra.dto.MandiRankResponse;
import com.krishimitra.dto.SellAdviceResponse;
import com.krishimitra.model.entity.SellRecommendation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-23T17:02:08+0000",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SellRecommendationMapperImpl implements SellRecommendationMapper {

    @Override
    public SellAdviceResponse toDto(SellRecommendation r) {
        if ( r == null ) {
            return null;
        }

        String sellDecision = null;
        Integer waitDays = null;
        BigDecimal currentPrice = null;
        BigDecimal profitGainPerQtl = null;
        BigDecimal storageCost = null;
        BigDecimal transportCost = null;
        BigDecimal netGain = null;
        String reasoning = null;

        sellDecision = r.getDecision();
        waitDays = r.getWaitDays();
        currentPrice = r.getCurrentPrice();
        profitGainPerQtl = r.getProfitGainPerQtl();
        storageCost = r.getStorageCost();
        transportCost = r.getTransportCost();
        netGain = r.getNetGain();
        reasoning = r.getReasoning();

        Integer peakDay = null;
        BigDecimal peakPrice = java.math.BigDecimal.ZERO;
        BigDecimal totalProfitGain = java.math.BigDecimal.ZERO;
        Double confidence = null;
        List<MandiRankResponse> alternativeMandis = null;
        Map<String, Object> explanation = null;

        SellAdviceResponse sellAdviceResponse = new SellAdviceResponse( sellDecision, waitDays, peakDay, currentPrice, peakPrice, profitGainPerQtl, totalProfitGain, storageCost, transportCost, netGain, confidence, reasoning, alternativeMandis, explanation );

        return sellAdviceResponse;
    }
}
