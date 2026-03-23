package com.krishimitra.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
