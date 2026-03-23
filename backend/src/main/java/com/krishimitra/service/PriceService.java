package com.krishimitra.service;

import com.krishimitra.client.MlEngineClient;
import com.krishimitra.dto.LivePriceResponse;
import com.krishimitra.dto.MandiRankResponse;
import com.krishimitra.dto.PriceForecastResponse;
import com.krishimitra.exception.ApiException;
import com.krishimitra.model.entity.Mandi;
import com.krishimitra.model.entity.MandiPrice;
import com.krishimitra.model.entity.PricePrediction;
import com.krishimitra.repository.MandiPriceRepository;
import com.krishimitra.repository.MandiRepository;
import com.krishimitra.repository.PricePredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceService {

    private final MandiPriceRepository priceRepo;
    private final MandiRepository mandiRepo;
    private final PricePredictionRepository predictionRepo;
    private final MlEngineClient mlClient;

    @Cacheable(value = "live-prices", key = "#commodity + ':' + #state")
    public List<LivePriceResponse> getLivePrices(String commodity, String state) {
        LocalDate today    = LocalDate.now();
        LocalDate weekAgo  = today.minusDays(7);

        List<MandiPrice> prices = priceRepo.findByCommoditySince(commodity, weekAgo);

        return prices.stream()
                .filter(p -> state == null || p.getMandi().getState().equalsIgnoreCase(state))
                .map(p -> {
                    // Quick trend calc: compare to 3 days ago
                    String trend = "FLAT";
                    BigDecimal changePct = BigDecimal.ZERO;
                    try {
                        var history = priceRepo.findHistory(
                                p.getMandi().getId(), commodity,
                                p.getPriceDate().minusDays(3), p.getPriceDate());
                        if (history.size() >= 2) {
                            BigDecimal prev = history.get(0).getModalPrice();
                            BigDecimal curr = history.get(history.size() - 1).getModalPrice();
                            changePct = curr.subtract(prev)
                                    .divide(prev, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100));
                            trend = changePct.compareTo(BigDecimal.ONE) > 0 ? "UP"
                                  : changePct.compareTo(BigDecimal.ONE.negate()) < 0 ? "DOWN"
                                  : "FLAT";
                        }
                    } catch (Exception ignored) {}

                    return new LivePriceResponse(
                            p.getCommodity(),
                            p.getMandi().getName(),
                            p.getMandi().getDistrict(),
                            p.getMandi().getState(),
                            p.getMinPrice(),
                            p.getMaxPrice(),
                            p.getModalPrice(),
                            p.getArrivalsQtl(),
                            p.getPriceDate().toString(),
                            trend,
                            changePct.setScale(2, RoundingMode.HALF_UP)
                    );
                })
                .toList();
    }

    @Cacheable(value = "price-forecast", key = "#commodity + ':' + #mandiName")
    public PriceForecastResponse getForecast(String commodity, String mandiName) {
        long start = System.currentTimeMillis();

        Mandi mandi = mandiRepo.findByNameIgnoreCaseAndStateIgnoreCase(mandiName, "")
                .orElseGet(() -> mandiRepo.findAll().stream()
                        .filter(m -> m.getName().equalsIgnoreCase(mandiName))
                        .findFirst()
                        .orElseThrow(() -> ApiException.notFound("Mandi not found: " + mandiName)));

        // Check for a fresh cached prediction in DB (30 min TTL)
        Optional<PricePrediction> cached = predictionRepo.findFreshPrediction(
                mandi.getId(), commodity, Instant.now());

        if (cached.isPresent()) {
            log.debug("DB cache hit for forecast: {}@{}", commodity, mandiName);
            return mapPrediction(cached.get(), true, System.currentTimeMillis() - start);
        }

        // Call ML engine
        log.info("Calling ML engine for {}@{}", commodity, mandiName);
        PriceForecastResponse mlResult = mlClient.getForecast(commodity, mandiName);

        // Persist prediction for 30 min
        // (actual persist would parse the ML response JSON — omitted for brevity,
        //  handled in PredictionPersistenceService)

        long latencyMs = System.currentTimeMillis() - start;
        return new PriceForecastResponse(
                mlResult.commodity(),
                mlResult.mandi(),
                mlResult.currentPrice(),
                mlResult.forecastDate(),
                mlResult.horizons(),
                mlResult.pointForecast(),
                mlResult.lower80(),
                mlResult.upper80(),
                mlResult.lower95(),
                mlResult.upper95(),
                mlResult.sellDecision(),
                mlResult.waitDays(),
                mlResult.peakDay(),
                mlResult.peakPrice(),
                mlResult.profitGain(),
                mlResult.confidence(),
                mlResult.explanation(),
                mlResult.modelWeights(),
                false,
                latencyMs
        );
    }

    public List<MandiRankResponse> rankMandis(
            String commodity, double lat, double lng, int topN) {

        List<Mandi> nearby = mandiRepo.findNearestMandis(lat, lng, topN * 2);

        return nearby.stream()
                .flatMap(mandi -> priceRepo.findLatest(mandi.getId(), commodity)
                        .stream()
                        .map(price -> {
                            double distKm = haversine(lat, lng,
                                    mandi.getLatitude(), mandi.getLongitude());
                            BigDecimal transportCost = estimateTransport(distKm, price.getModalPrice());
                            BigDecimal netPrice = price.getModalPrice().subtract(transportCost);
                            return new MandiRankResponse(
                                    mandi.getName(), mandi.getState(),
                                    price.getModalPrice(), netPrice,
                                    Math.round(distKm * 10.0) / 10.0,
                                    transportCost, 75,
                                    netPrice.compareTo(price.getModalPrice().multiply(BigDecimal.valueOf(0.9))) > 0
                                            ? "RECOMMENDED" : "OK"
                            );
                        }))
                .sorted(Comparator.comparing(MandiRankResponse::netPrice).reversed())
                .limit(topN)
                .toList();
    }

    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng/2) * Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private BigDecimal estimateTransport(double distKm, BigDecimal price) {
        // ~₹15/km for a 5-tonne truck carrying 50 quintals = ₹0.30/qtl/km
        return BigDecimal.valueOf(distKm * 0.30).setScale(2, RoundingMode.HALF_UP);
    }

    private PriceForecastResponse mapPrediction(
            PricePrediction p, boolean fromCache, long latencyMs) {
        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            java.util.List<BigDecimal> forecast =
                    om.readValue(p.getPointForecast() != null ? p.getPointForecast() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<BigDecimal> lo80 =
                    om.readValue(p.getLower80() != null ? p.getLower80() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<BigDecimal> hi80 =
                    om.readValue(p.getUpper80() != null ? p.getUpper80() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<BigDecimal> lo95 =
                    om.readValue(p.getLower95() != null ? p.getLower95() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<BigDecimal> hi95 =
                    om.readValue(p.getUpper95() != null ? p.getUpper95() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return new PriceForecastResponse(
                    p.getCommodity(),
                    p.getMandi() != null ? p.getMandi().getName() : "",
                    p.getCurrentPrice(),
                    p.getGeneratedAt() != null ? p.getGeneratedAt().toString() : "",
                    null,
                    forecast,
                    lo80, hi80,
                    lo95, hi95,
                    p.getSellDecision(),
                    p.getWaitDays() != null ? p.getWaitDays() : 0,
                    p.getPeakDay() != null ? p.getPeakDay() : 0,
                    p.getPeakPrice(),
                    p.getProfitGain(),
                    p.getConfidence() != null ? p.getConfidence().doubleValue() : 0.0,
                    null,
                    null,
                    fromCache,
                    latencyMs
            );
        } catch (Exception e) {
            log.error("Failed to deserialise prediction JSON: {}", e.getMessage());
            return null;
        }
    }
}
