package com.krishimitra.mapper;

import com.krishimitra.dto.PriceForecastResponse;
import com.krishimitra.model.entity.Mandi;
import com.krishimitra.model.entity.PricePrediction;
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
public class PricePredictionMapperImpl implements PricePredictionMapper {

    @Override
    public PriceForecastResponse toDto(PricePrediction entity) {
        if ( entity == null ) {
            return null;
        }

        String commodity = null;
        String mandi = null;
        BigDecimal currentPrice = null;
        List<BigDecimal> pointForecast = null;
        List<BigDecimal> lower80 = null;
        List<BigDecimal> upper80 = null;
        List<BigDecimal> lower95 = null;
        List<BigDecimal> upper95 = null;
        String sellDecision = null;
        Integer waitDays = null;
        Integer peakDay = null;
        Double confidence = null;

        commodity = entity.getCommodity();
        mandi = entityMandiName( entity );
        currentPrice = entity.getCurrentPrice();
        pointForecast = PricePredictionMapper.jsonToBigDecimalList( entity.getPointForecast() );
        lower80 = PricePredictionMapper.jsonToBigDecimalList( entity.getLower80() );
        upper80 = PricePredictionMapper.jsonToBigDecimalList( entity.getUpper80() );
        lower95 = PricePredictionMapper.jsonToBigDecimalList( entity.getLower95() );
        upper95 = PricePredictionMapper.jsonToBigDecimalList( entity.getUpper95() );
        sellDecision = entity.getSellDecision();
        waitDays = entity.getWaitDays();
        peakDay = entity.getPeakDay();
        if ( entity.getConfidence() != null ) {
            confidence = entity.getConfidence().doubleValue();
        }

        String forecastDate = entity.getGeneratedAt() != null ? entity.getGeneratedAt().toString() : "";
        List<Integer> horizons = null;
        BigDecimal peakPrice = entity.getPeakPrice() != null ? entity.getPeakPrice() : java.math.BigDecimal.ZERO;
        BigDecimal profitGain = entity.getProfitGain() != null ? entity.getProfitGain() : java.math.BigDecimal.ZERO;
        boolean fromCache = true;
        Map<String, Object> explanation = null;
        Map<String, Double> modelWeights = null;
        long latencyMs = 0L;

        PriceForecastResponse priceForecastResponse = new PriceForecastResponse( commodity, mandi, currentPrice, forecastDate, horizons, pointForecast, lower80, upper80, lower95, upper95, sellDecision, waitDays, peakDay, peakPrice, profitGain, confidence, explanation, modelWeights, fromCache, latencyMs );

        return priceForecastResponse;
    }

    private String entityMandiName(PricePrediction pricePrediction) {
        if ( pricePrediction == null ) {
            return null;
        }
        Mandi mandi = pricePrediction.getMandi();
        if ( mandi == null ) {
            return null;
        }
        String name = mandi.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }
}
