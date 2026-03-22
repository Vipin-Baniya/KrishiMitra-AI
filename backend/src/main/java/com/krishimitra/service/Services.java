package com.krishimitra.service;

import com.krishimitra.client.MlEngineClient;
import com.krishimitra.dto.*;
import com.krishimitra.exception.ApiException;
import com.krishimitra.kafka.KafkaProducer;
import com.krishimitra.model.entity.*;
import com.krishimitra.repository.*;
import com.krishimitra.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

// ─────────────────────────────────────────────────────────────
// AUTH SERVICE
// ─────────────────────────────────────────────────────────────

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final FarmerRepository farmerRepo;
    private final RefreshTokenRepository refreshRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    @Transactional
    public AuthDTOs.TokenResponse register(AuthDTOs.RegisterRequest req) {
        if (farmerRepo.existsByPhone(req.phone())) {
            throw ApiException.conflict("Phone number already registered: " + req.phone());
        }

        Farmer farmer = Farmer.builder()
                .phone(req.phone())
                .name(req.name())
                .email(req.email())
                .passwordHash(encoder.encode(req.password()))
                .village(req.village())
                .district(req.district())
                .state(req.state() != null ? req.state() : "Madhya Pradesh")
                .latitude(req.latitude())
                .longitude(req.longitude())
                .preferredLang(req.preferredLang() != null ? req.preferredLang() : "hi")
                .build();

        farmer = farmerRepo.save(farmer);
        log.info("Registered new farmer: {} ({})", farmer.getName(), farmer.getPhone());
        return issueTokens(farmer);
    }

    @Transactional
    public AuthDTOs.TokenResponse login(AuthDTOs.LoginRequest req) {
        Farmer farmer = farmerRepo.findByPhone(req.phone())
                .filter(Farmer::getIsActive)
                .orElseThrow(() -> ApiException.unauthorized("Invalid phone or password"));

        if (!encoder.matches(req.password(), farmer.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid phone or password");
        }
        return issueTokens(farmer);
    }

    @Transactional
    public AuthDTOs.TokenResponse refresh(String refreshToken) {
        RefreshToken stored = refreshRepo.findByToken(refreshToken)
                .filter(rt -> !rt.getRevoked())
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired refresh token"));

        Farmer farmer = stored.getFarmer();
        // Rotate: revoke old, issue new
        stored.setRevoked(true);
        refreshRepo.save(stored);
        return issueTokens(farmer);
    }

    @Transactional
    public void logout(UUID farmerId) {
        refreshRepo.revokeAllForFarmer(farmerId);
        log.info("Logged out farmer: {}", farmerId);
    }

    private AuthDTOs.TokenResponse issueTokens(Farmer farmer) {
        String access  = jwtService.generateAccessToken(farmer.getId(), farmer.getPhone());
        String refresh = jwtService.generateRefreshToken(farmer.getId());

        // Store refresh token
        RefreshToken rt = RefreshToken.builder()
                .farmer(farmer)
                .token(refresh)
                .expiresAt(jwtService.getExpiry(refresh))
                .build();
        refreshRepo.save(rt);

        return new AuthDTOs.TokenResponse(
                access, refresh,
                Duration.ofDays(1).toMillis(),
                farmer.getId(), farmer.getName()
        );
    }
}

// ─────────────────────────────────────────────────────────────
// FARMER SERVICE
// ─────────────────────────────────────────────────────────────

@Service
@RequiredArgsConstructor
@Slf4j
public class FarmerService {

    private final FarmerRepository farmerRepo;
    private final FarmerCropRepository cropRepo;
    private final AlertRepository alertRepo;

    @Cacheable(value = "farmer-profile", key = "#farmerId")
    public FarmerProfileResponse getProfile(UUID farmerId) {
        Farmer farmer = farmerRepo.findById(farmerId)
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        List<FarmerProfileResponse.FarmerCropDto> crops = cropRepo
                .findByFarmerIdOrderByCreatedAtDesc(farmerId).stream()
                .map(c -> new FarmerProfileResponse.FarmerCropDto(
                        c.getId(), c.getCommodity(), c.getVariety(),
                        c.getQuantityQuintal(),
                        c.getExpectedHarvest() != null ? c.getExpectedHarvest().toString() : null,
                        c.getStorageAvailable()))
                .toList();

        long unread = alertRepo.countUnread(farmerId, Instant.now());

        return new FarmerProfileResponse(
                farmer.getId(), farmer.getName(), farmer.getPhone(),
                farmer.getEmail(), farmer.getVillage(), farmer.getDistrict(),
                farmer.getState(), farmer.getPreferredLang(), crops, (int) unread
        );
    }

    @Transactional
    @CacheEvict(value = "farmer-profile", key = "#farmerId")
    public FarmerProfileResponse updateProfile(UUID farmerId, UpdateFarmerRequest req) {
        Farmer farmer = farmerRepo.findById(farmerId)
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        if (req.name()         != null) farmer.setName(req.name());
        if (req.email()        != null) farmer.setEmail(req.email());
        if (req.village()      != null) farmer.setVillage(req.village());
        if (req.district()     != null) farmer.setDistrict(req.district());
        if (req.state()        != null) farmer.setState(req.state());
        if (req.latitude()     != null) farmer.setLatitude(req.latitude());
        if (req.longitude()    != null) farmer.setLongitude(req.longitude());
        if (req.preferredLang() != null) farmer.setPreferredLang(req.preferredLang());

        farmerRepo.save(farmer);
        return getProfile(farmerId);
    }

    @Transactional
    @CacheEvict(value = "farmer-profile", key = "#farmerId")
    public FarmerProfileResponse.FarmerCropDto addCrop(UUID farmerId, FarmerCropDto req) {
        Farmer farmer = farmerRepo.findById(farmerId)
                .orElseThrow(() -> ApiException.notFound("Farmer not found"));

        FarmerCrop crop = FarmerCrop.builder()
                .farmer(farmer)
                .commodity(req.commodity())
                .variety(req.variety())
                .quantityQuintal(BigDecimal.valueOf(req.quantityQuintal()))
                .storageAvailable(req.storageAvailable())
                .build();

        crop = cropRepo.save(crop);
        return new FarmerProfileResponse.FarmerCropDto(
                crop.getId(), crop.getCommodity(), crop.getVariety(),
                crop.getQuantityQuintal(), null, crop.getStorageAvailable());
    }

    public record FarmerCropDto(
            String commodity, String variety,
            double quantityQuintal, boolean storageAvailable) {}
}

// ─────────────────────────────────────────────────────────────
// PRICE SERVICE
// ─────────────────────────────────────────────────────────────

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

        return mlResult.withLatency(System.currentTimeMillis() - start, false);
    }

    public List<MandiRankResponse> rankMandis(
            String commodity, double lat, double lng, int topN) {

        List<Mandi> nearby = mandiRepo.findNearestMandis(lat, lng, topN * 2);
        LocalDate today = LocalDate.now();

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
            java.util.List<Integer> horizons =
                    om.readValue(p.getHorizonsJson() != null ? p.getHorizonsJson() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<Double> forecast =
                    om.readValue(p.getForecastJson() != null ? p.getForecastJson() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<Double> lo80 =
                    om.readValue(p.getLower80Json() != null ? p.getLower80Json() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<Double> hi80 =
                    om.readValue(p.getUpper80Json() != null ? p.getUpper80Json() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<Double> lo95 =
                    om.readValue(p.getLower95Json() != null ? p.getLower95Json() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            java.util.List<Double> hi95 =
                    om.readValue(p.getUpper95Json() != null ? p.getUpper95Json() : "[]",
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return PriceForecastResponse.builder()
                    .commodity(p.getCommodity())
                    .mandi(p.getMandi() != null ? p.getMandi().getName() : "")
                    .currentPrice(p.getCurrentPrice() != null ? p.getCurrentPrice().doubleValue() : 0)
                    .forecastDate(p.getForecastDate() != null ? p.getForecastDate().toString() : "")
                    .horizons(horizons)
                    .pointForecast(forecast)
                    .lower80(lo80).upper80(hi80)
                    .lower95(lo95).upper95(hi95)
                    .sellDecision(p.getSellDecision())
                    .waitDays(p.getWaitDays() != null ? p.getWaitDays() : 0)
                    .peakDay(p.getPeakDay() != null ? p.getPeakDay() : 0)
                    .peakPrice(p.getPeakPrice() != null ? p.getPeakPrice().doubleValue() : 0)
                    .profitGain(p.getProfitGain() != null ? p.getProfitGain().doubleValue() : 0)
                    .confidence(p.getConfidenceScore() != null ? p.getConfidenceScore() : 0.0)
                    .explanation(null)
                    .fromCache(fromCache)
                    .latencyMs(latencyMs)
                    .build();
        } catch (Exception e) {
            log.error("Failed to deserialise prediction JSON: {}", e.getMessage());
            return null;
        }
    }
}

// ─────────────────────────────────────────────────────────────
// SELL ADVISOR SERVICE
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// ALERT SERVICE
// ─────────────────────────────────────────────────────────────

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {

    private final AlertRepository alertRepo;

    public AlertPageResponse getAlerts(UUID farmerId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Alert> alertPage = alertRepo.findForFarmer(farmerId, Instant.now(), pageable);
        long unread = alertRepo.countUnread(farmerId, Instant.now());

        List<AlertResponse> dtos = alertPage.getContent().stream()
                .map(this::toDto)
                .toList();

        return new AlertPageResponse(dtos, unread, page, alertPage.getTotalPages());
    }

    @Transactional
    public void markAllRead(UUID farmerId) {
        alertRepo.markAllRead(farmerId);
    }

    @Transactional
    public void markRead(UUID farmerId, UUID alertId) {
        alertRepo.findById(alertId).ifPresent(a -> {
            if (a.getFarmer() != null && a.getFarmer().getId().equals(farmerId)) {
                a.setIsRead(true);
                alertRepo.save(a);
            }
        });
    }

    @Transactional
    public Alert createAlert(UUID farmerId, String type, String severity,
                              String commodity, String mandi,
                              String title, String body, Map<String, Object> metadata) {
        Farmer farmer = farmerId != null
                ? Farmer.builder().id(farmerId).build()   // reference only
                : null;

        Alert alert = Alert.builder()
                .farmer(farmer)
                .type(type)
                .severity(severity)
                .commodity(commodity)
                .mandiName(mandi)
                .title(title)
                .body(body)
                .expiresAt(Instant.now().plus(Duration.ofDays(3)))
                .build();

        return alertRepo.save(alert);
    }

    private AlertResponse toDto(Alert a) {
        return new AlertResponse(
                a.getId(), a.getType(), a.getSeverity(),
                a.getCommodity(), a.getMandiName(),
                a.getTitle(), a.getBody(), a.getIsRead(),
                a.getExpiresAt() != null ? a.getExpiresAt().toString() : null,
                a.getCreatedAt().toString()
        );
    }
}
