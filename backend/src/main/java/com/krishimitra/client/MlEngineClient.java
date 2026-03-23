package com.krishimitra.client;

import com.krishimitra.dto.PriceForecastResponse;
import com.krishimitra.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class MlEngineClient {

    private final WebClient webClient;

    public MlEngineClient(
            @Value("${krishimitra.ml-engine.base-url}") String baseUrl,
            @Value("${krishimitra.ml-engine.connect-timeout-ms}") int connectTimeout,
            @Value("${krishimitra.ml-engine.read-timeout-ms}") int readTimeout,
            @Value("${krishimitra.ml-engine.retry-attempts}") int retryAttempts) {

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    public PriceForecastResponse getForecast(String commodity, String mandi) {
        log.debug("ML forecast request: {}@{}", commodity, mandi);

        MlForecastRequest req = new MlForecastRequest(commodity, mandi, 30, false);

        return webClient.post()
                .uri("/forecast")
                .bodyValue(req)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp ->
                        Mono.error(ApiException.notFound("No ML model for " + commodity + "@" + mandi)))
                .onStatus(HttpStatusCode::is5xxServerError, resp ->
                        Mono.error(new RuntimeException("ML engine error")))
                .bodyToMono(MlForecastRaw.class)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                        .filter(t -> !(t instanceof ApiException)))
                .timeout(Duration.ofSeconds(5))
                .map(this::mapToForecastResponse)
                .doOnError(e -> log.error("ML engine call failed: {}", e.getMessage()))
                .onErrorReturn(buildFallbackForecast(commodity, mandi))
                .block();
    }

    private PriceForecastResponse mapToForecastResponse(MlForecastRaw raw) {
        return new PriceForecastResponse(
                raw.commodity(), raw.mandi(),
                raw.currentPrice() != null ? raw.currentPrice() : java.math.BigDecimal.ZERO,
                raw.forecastDate(),
                raw.horizons() != null ? raw.horizons() : List.of(1, 3, 7, 14, 21, 30),
                raw.pointForecast(), raw.lower80(), raw.upper80(),
                raw.lower95(), raw.upper95(),
                raw.sellDecision(), raw.waitDays(), raw.peakDay(),
                raw.peakPrice(), raw.profitGain(),
                raw.confidence(),
                raw.explanation(), raw.modelWeights(),
                false, raw.latencyMs() != null ? raw.latencyMs() : 0L
        );
    }

    private PriceForecastResponse buildFallbackForecast(String commodity, String mandi) {
        // If ML engine is down — return a minimal response with null forecasts
        // The service layer will handle this gracefully
        log.warn("ML engine unreachable — returning null forecast for {}@{}", commodity, mandi);
        return null;
    }

    // ── Internal DTOs ──────────────────────────────────────────

    record MlForecastRequest(String commodity, String mandi, int horizon, boolean forceFresh) {}

    record MlForecastRaw(
            String commodity, String mandi,
            java.math.BigDecimal currentPrice, String forecastDate,
            List<Integer> horizons,
            List<java.math.BigDecimal> pointForecast,
            List<java.math.BigDecimal> lower80, List<java.math.BigDecimal> upper80,
            List<java.math.BigDecimal> lower95, List<java.math.BigDecimal> upper95,
            String sellDecision, Integer waitDays, Integer peakDay,
            java.math.BigDecimal peakPrice, java.math.BigDecimal profitGain,
            Double confidence,
            Map<String, Object> explanation, Map<String, Double> modelWeights,
            Long latencyMs
    ) {}
}
