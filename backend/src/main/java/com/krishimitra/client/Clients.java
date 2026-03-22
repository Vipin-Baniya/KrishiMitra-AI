package com.krishimitra.client;

import com.krishimitra.dto.*;
import com.krishimitra.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;

// ─────────────────────────────────────────────────────────────
// ML ENGINE CLIENT  (calls Python FastAPI on port 8003)
// ─────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────
// LLM INFERENCE CLIENT  (calls Python FastAPI on port 8001)
// ─────────────────────────────────────────────────────────────

@Component
@Slf4j
public class LlmClient {

    private final WebClient webClient;

    public LlmClient(
            @Value("${krishimitra.llm-engine.base-url}") String baseUrl,
            @Value("${krishimitra.llm-engine.read-timeout-ms}") int readTimeout) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    public LlmResponse chat(LlmRequest request) {
        log.debug("LLM chat request, lang={}", request.language());

        return webClient.post()
                .uri("/v1/chat")
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new RuntimeException("LLM engine returned " + resp.statusCode())))
                .bodyToMono(LlmResponse.class)
                .retryWhen(Retry.backoff(1, Duration.ofSeconds(1)))
                .timeout(Duration.ofSeconds(12))
                .doOnError(e -> log.error("LLM client error: {}", e.getMessage()))
                .block();
    }

    public record LlmRequest(
            List<Map<String, String>> messages,
            String language,
            Object context,
            int maxTokens,
            double temperature
    ) {}

    public record LlmResponse(
            String content,
            String modelUsed,
            Long latencyMs,
            Double confidence,
            String fallbackReason,
            Integer tokensUsed
    ) {}
}

// ─────────────────────────────────────────────────────────────
// AGMARKNET CLIENT  (Data.gov.in Agmarknet open API)
// ─────────────────────────────────────────────────────────────

@Component
@Slf4j
public class AgmarknetClient {

    private final WebClient webClient;
    private final String apiKey;

    private static final String BASE_URL =
            "https://api.data.gov.in/resource/9ef84268-d588-465a-a308-a864a43d0070";

    public AgmarknetClient(
            @Value("${krishimitra.agmarknet.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    /**
     * Fetch mandi prices for a commodity.
     * Paginates automatically until all records for the date range are fetched.
     * Returns raw records for processing by AgmarknetIngestionService.
     */
    public List<AgmarknetRecord> fetchPrices(
            String commodity,
            String state,
            String fromDate,    // dd/mm/yyyy
            String toDate) {

        List<AgmarknetRecord> all = new ArrayList<>();
        int offset = 0, limit = 100;

        while (true) {
            var params = buildParams(commodity, state, fromDate, toDate, offset, limit);
            AgmarknetResponse resp = webClient.get()
                    .uri(uriBuilder -> {
                        params.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(AgmarknetResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2)))
                    .doOnError(e -> log.warn("Agmarknet API error (offset={}): {}", offset, e.getMessage()))
                    .onErrorReturn(new AgmarknetResponse(List.of(), 0))
                    .block();

            if (resp == null || resp.records() == null || resp.records().isEmpty()) break;

            all.addAll(resp.records());
            if (resp.records().size() < limit) break;

            offset += limit;
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }

        log.info("Agmarknet: fetched {} records for {} ({} → {})", all.size(), commodity, fromDate, toDate);
        return all;
    }

    private Map<String, Object> buildParams(
            String commodity, String state,
            String from, String to, int offset, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("api-key", apiKey);
        p.put("format", "json");
        p.put("limit", limit);
        p.put("offset", offset);
        p.put("filters[commodity]", commodity);
        if (state != null) p.put("filters[state]", state);
        if (from != null && to != null) {
            p.put("filters[arrival_date]", from + " TO " + to);
        }
        return p;
    }

    // ── Raw API response DTOs ──────────────────────────────────

    public record AgmarknetRecord(
            String arrival_date,
            String state,
            String district,
            String market,
            String commodity,
            String variety,
            String min_price,
            String max_price,
            String modal_price,
            String arrivals_in_qtl
    ) {}

    record AgmarknetResponse(
            List<AgmarknetRecord> records,
            int total
    ) {}
}
