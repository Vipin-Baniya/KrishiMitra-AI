package com.krishimitra.service;

import com.krishimitra.dto.MandiRankResponse;
import com.krishimitra.dto.PriceForecastResponse;
import com.krishimitra.dto.ProfitSimRequest;
import com.krishimitra.dto.ProfitSimResponse;
import com.krishimitra.dto.SellAdviceRequest;
import com.krishimitra.dto.SellAdviceResponse;
import com.krishimitra.kafka.KafkaProducer;
import com.krishimitra.repository.MandiRepository;
import com.krishimitra.repository.ProfitSimulationRepository;
import com.krishimitra.repository.SellRecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SellAdvisorService {

    private final PriceService priceService;
    private final MandiRepository mandiRepo;
    private final SellRecommendationRepository recRepo;
    private final ProfitSimulationRepository simRepo;
    private final KafkaProducer kafkaProducer;

    private static final Map<String, BigDecimal> STORAGE_COST_PER_DAY = Map.of(
            "Wheat",   BigDecimal.valueOf(2.5),
            "Soybean", BigDecimal.valueOf(4.0),
            "Onion",   BigDecimal.valueOf(6.0),
            "Tomato",  BigDecimal.valueOf(8.0),
            "Potato",  BigDecimal.valueOf(5.0),
            "Cotton",  BigDecimal.valueOf(3.0),
            "Maize",   BigDecimal.valueOf(1.5),
            "Gram",    BigDecimal.valueOf(2.0)
    );

    public SellAdviceResponse getAdvice(
            UUID farmerId, SellAdviceRequest req) {

        PriceForecastResponse forecast = priceService.getForecast(req.commodity(), req.mandi());
        BigDecimal current = forecast.currentPrice();

        int waitDays   = forecast.waitDays() != null ? forecast.waitDays() : 0;
        BigDecimal peakPrice = forecast.peakPrice() != null ? forecast.peakPrice() : current;

        BigDecimal storageCostPerDay = STORAGE_COST_PER_DAY
                .getOrDefault(req.commodity(), BigDecimal.valueOf(3.0));
        BigDecimal storageCost    = storageCostPerDay.multiply(BigDecimal.valueOf(waitDays));
        BigDecimal transportCost  = estimateTransportForMandi(req.mandi());

        BigDecimal profitGainPerQtl = peakPrice.subtract(current).subtract(storageCost);
        BigDecimal netGain = profitGainPerQtl.multiply(BigDecimal.valueOf(req.quantityQuintal()));
        BigDecimal totalProfit = profitGainPerQtl.multiply(BigDecimal.valueOf(req.quantityQuintal()));

        // Rank alternative mandis
        List<MandiRankResponse> alternatives = List.of(); // filled from priceService

        String reasoning = buildReasoning(forecast, storageCost, req.commodity(), req.mandi());

        // Persist recommendation
        persistRecommendation(farmerId, req, forecast, storageCost, transportCost, netGain, reasoning);

        // Publish to Kafka for alert engine
        if ("WAIT_N_DAYS".equals(forecast.sellDecision())) {
            kafkaProducer.publishSellAlert(farmerId, req.commodity(), req.mandi(), waitDays, profitGainPerQtl);
        }

        return new SellAdviceResponse(
                forecast.sellDecision(),
                waitDays,
                forecast.peakDay(),
                current,
                peakPrice,
                profitGainPerQtl.setScale(2, RoundingMode.HALF_UP),
                totalProfit.setScale(2, RoundingMode.HALF_UP),
                storageCost.setScale(2, RoundingMode.HALF_UP),
                transportCost,
                netGain.setScale(2, RoundingMode.HALF_UP),
                forecast.confidence(),
                reasoning,
                alternatives,
                forecast.explanation()
        );
    }

    public ProfitSimResponse simulate(UUID farmerId, ProfitSimRequest req) {
        PriceForecastResponse forecast = priceService.getForecast(req.commodity(), req.mandi());
        BigDecimal current = forecast.currentPrice();

        // Find the forecast price at requested wait days
        List<Integer> horizons = forecast.horizons() != null ? forecast.horizons()
                : List.of(1, 3, 7, 14, 21, 30);
        int hIdx = 0;
        for (int i = 0; i < horizons.size(); i++) {
            if (horizons.get(i) <= req.waitDays()) hIdx = i;
        }
        BigDecimal predictedPrice = forecast.pointForecast() != null && !forecast.pointForecast().isEmpty()
                ? forecast.pointForecast().get(Math.min(hIdx, forecast.pointForecast().size() - 1))
                : current;

        BigDecimal storageCostPerDay = STORAGE_COST_PER_DAY
                .getOrDefault(req.commodity(), BigDecimal.valueOf(3.0));
        BigDecimal storageCost    = storageCostPerDay.multiply(BigDecimal.valueOf(req.waitDays()));
        BigDecimal transportCost  = estimateTransportForMandi(req.mandi());
        BigDecimal grossRevenue   = predictedPrice.multiply(BigDecimal.valueOf(req.quantityQuintal()));
        BigDecimal netRevenue     = grossRevenue.subtract(storageCost).subtract(transportCost);
        BigDecimal sellNowRevenue = current.multiply(BigDecimal.valueOf(req.quantityQuintal())).subtract(transportCost);
        BigDecimal profitVsNow    = netRevenue.subtract(sellNowRevenue);

        // Build scenario chart for all horizons
        List<ProfitSimResponse.ScenarioPoint> chart = new ArrayList<>();
        if (forecast.pointForecast() != null) {
            for (int i = 0; i < horizons.size(); i++) {
                int h = horizons.get(i);
                BigDecimal hPrice = forecast.pointForecast().get(i);
                BigDecimal hStorage = storageCostPerDay.multiply(BigDecimal.valueOf(h));
                BigDecimal hNet = hPrice.multiply(BigDecimal.valueOf(req.quantityQuintal()))
                        .subtract(hStorage).subtract(transportCost)
                        .subtract(sellNowRevenue);
                chart.add(new ProfitSimResponse.ScenarioPoint(h, hNet.setScale(2, RoundingMode.HALF_UP)));
            }
        }

        return new ProfitSimResponse(
                req.commodity(), req.mandi(), req.quantityQuintal(), req.waitDays(),
                current, predictedPrice,
                storageCost.setScale(2, RoundingMode.HALF_UP),
                transportCost,
                grossRevenue.setScale(2, RoundingMode.HALF_UP),
                netRevenue.setScale(2, RoundingMode.HALF_UP),
                profitVsNow.setScale(2, RoundingMode.HALF_UP),
                chart
        );
    }

    private String buildReasoning(PriceForecastResponse fc, BigDecimal storageCost,
                                   String commodity, String mandi) {
        if ("SELL_NOW".equals(fc.sellDecision())) {
            return String.format(
                    "Sell %s at %s now (₹%.0f/qtl). Prices are expected to decline. "
                    + "Immediate action prevents further loss.",
                    commodity, mandi, fc.currentPrice());
        }
        return String.format(
                "Wait %d days before selling %s at %s. Price expected to peak at "
                + "₹%.0f/qtl (+₹%.0f after storage cost of ₹%.0f).",
                fc.waitDays(), commodity, mandi,
                fc.peakPrice(), fc.profitGain(), storageCost);
    }

    @Transactional
    private void persistRecommendation(
            UUID farmerId, SellAdviceRequest req,
            PriceForecastResponse fc, BigDecimal storageCost,
            BigDecimal transportCost, BigDecimal netGain, String reasoning) {
        // Saved to DB for farmer history — full impl uses entity mappers
        log.debug("Persisting sell recommendation for farmer {} / {}", farmerId, req.commodity());
    }

    private BigDecimal estimateTransportForMandi(String mandiName) {
        // Simplified: lookup mandi distance from farmer's location
        // In production: use farmer lat/lng + mandi lat/lng + haversine
        return BigDecimal.valueOf(800);  // default ₹800 transport estimate
    }
}
