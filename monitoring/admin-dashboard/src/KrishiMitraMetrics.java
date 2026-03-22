package com.krishimitra.monitoring;

import io.micrometer.core.instrument.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * KrishiMitra Custom Metrics
 * ===========================
 * All business + operational metrics published to Prometheus via Micrometer.
 *
 * Naming convention: krishimitra_{domain}_{noun}_{unit_or_type}
 *
 * Register as a Spring bean — inject wherever you need to record events.
 *
 * Usage:
 *   @Autowired KrishiMitraMetrics metrics;
 *   metrics.recordSellAdvice("Wheat", "Indore", "WAIT_N_DAYS");
 *   metrics.recordLlmRequest("custom", "success", 420);
 */
@Component
@Slf4j
public class KrishiMitraMetrics {

    private final MeterRegistry registry;

    // ── Farmer ────────────────────────────────────────────────
    private final Counter farmerRegistrations;
    private final Counter farmerLogins;
    private final AtomicLong totalFarmers;

    // ── Sell Advisor ──────────────────────────────────────────
    private final Counter sellAdvisories;

    // ── Price / ML ────────────────────────────────────────────
    private final Counter priceQueries;
    private final Counter mandiQueries;
    private final Counter forecastRequests;
    private final Counter agmarknetRecordsIngested;
    private final Counter agmarknetIngestErrors;
    private final Timer   forecastLatency;

    // ── LLM ──────────────────────────────────────────────────
    private final Counter chatMessages;
    private final Timer   llmLatency;

    // ── Alerts ────────────────────────────────────────────────
    private final Counter alertsCreated;

    // ── Timestamps for staleness checks ──────────────────────
    @Getter private volatile long lastAgmarknetIngestTs = 0;
    @Getter private volatile long lastPredictionGeneratedTs = 0;

    public KrishiMitraMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Farmer counters
        farmerRegistrations = Counter.builder("krishimitra.farmer.registrations")
                .description("Total farmer registrations").register(registry);
        farmerLogins = Counter.builder("krishimitra.farmer.logins")
                .description("Total farmer login events").register(registry);
        totalFarmers = registry.gauge("krishimitra.farmers", new AtomicLong(0));

        // Sell advisor
        sellAdvisories = Counter.builder("krishimitra.sell.advisories")
                .description("Sell advice requests served")
                .tag("decision", "unknown")
                .register(registry);

        // Price queries
        priceQueries   = Counter.builder("krishimitra.price.queries")
                .description("Price lookup requests").register(registry);
        mandiQueries   = Counter.builder("krishimitra.mandi.queries")
                .description("Mandi-specific price queries").register(registry);
        forecastRequests = Counter.builder("krishimitra.forecast.requests")
                .description("ML price forecast requests").register(registry);

        // Agmarknet ingestion
        agmarknetRecordsIngested = Counter.builder("krishimitra.agmarknet.records.ingested")
                .description("Mandi price records successfully ingested").register(registry);
        agmarknetIngestErrors = Counter.builder("krishimitra.agmarknet.ingest.errors")
                .description("Agmarknet ingestion failures").register(registry);

        // LLM / chat
        chatMessages = Counter.builder("krishimitra.chat.messages")
                .description("AI chat messages processed")
                .tag("language", "unknown")
                .register(registry);

        // Alerts
        alertsCreated = Counter.builder("krishimitra.alerts.created")
                .description("Smart alerts created").register(registry);

        // Timers
        forecastLatency = Timer.builder("krishimitra.forecast.latency")
                .description("End-to-end price forecast latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        llmLatency = Timer.builder("krishimitra.llm.latency")
                .description("LLM inference latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // Staleness gauges
        Gauge.builder("krishimitra.last.agmarknet.ingest.timestamp.seconds",
                this, m -> m.lastAgmarknetIngestTs / 1000.0)
                .description("Unix timestamp of last successful Agmarknet ingestion")
                .register(registry);
        Gauge.builder("krishimitra.last.prediction.generated.timestamp.seconds",
                this, m -> m.lastPredictionGeneratedTs / 1000.0)
                .description("Unix timestamp of last ML prediction generated")
                .register(registry);
    }

    // ── Recording methods ─────────────────────────────────────

    public void recordFarmerRegistered() {
        farmerRegistrations.increment();
        totalFarmers.incrementAndGet();
    }

    public void recordFarmerLogin() {
        farmerLogins.increment();
    }

    public void setTotalFarmers(long count) {
        totalFarmers.set(count);
    }

    /** Record a sell advice event with the commodity, mandi, and decision. */
    public void recordSellAdvice(String commodity, String mandi, String decision) {
        Counter.builder("krishimitra.sell.advisories")
                .tag("commodity", commodity)
                .tag("mandi",     mandi)
                .tag("decision",  decision)
                .register(registry)
                .increment();
    }

    /** Record a price query (live price lookup). */
    public void recordPriceQuery(String commodity, String state) {
        Counter.builder("krishimitra.price.queries")
                .tag("commodity", commodity)
                .tag("state",     state)
                .register(registry)
                .increment();
    }

    /** Record a mandi-specific query (price ranking, nearest mandi). */
    public void recordMandiQuery(String commodity, String mandi) {
        Counter.builder("krishimitra.mandi.queries")
                .tag("commodity", commodity)
                .tag("mandi",     mandi)
                .register(registry)
                .increment();
    }

    /** Record a forecast request and its latency. */
    public void recordForecastRequest(String commodity, String mandi, long latencyMs) {
        Counter.builder("krishimitra.forecast.requests")
                .tag("commodity", commodity)
                .tag("mandi",     mandi)
                .register(registry)
                .increment();
        forecastLatency.record(java.time.Duration.ofMillis(latencyMs));
    }

    /** Called after each successful Agmarknet batch ingest. */
    public void recordAgmarknetIngestion(String commodity, String state, int records) {
        Counter.builder("krishimitra.agmarknet.records.ingested")
                .tag("commodity", commodity)
                .tag("state",     state)
                .register(registry)
                .increment(records);
        lastAgmarknetIngestTs = System.currentTimeMillis();
        log.debug("Recorded Agmarknet ingestion: {} records for {}@{}", records, commodity, state);
    }

    public void recordAgmarknetError(String commodity, String state, String error) {
        Counter.builder("krishimitra.agmarknet.ingest.errors")
                .tag("commodity", commodity)
                .tag("state",     state)
                .tag("error",     error.substring(0, Math.min(error.length(), 40)))
                .register(registry)
                .increment();
    }

    /** Record an AI chat message with language and model used. */
    public void recordChatMessage(String language, String modelUsed, long latencyMs) {
        Counter.builder("krishimitra.chat.messages")
                .tag("language",   language)
                .tag("model_used", modelUsed)
                .register(registry)
                .increment();
        llmLatency.record(java.time.Duration.ofMillis(latencyMs));
    }

    /** Record an alert being created. */
    public void recordAlertCreated(String type, String severity, String commodity) {
        Counter.builder("krishimitra.alerts.created")
                .tag("type",      type)
                .tag("severity",  severity)
                .tag("commodity", commodity != null ? commodity : "general")
                .register(registry)
                .increment();
    }

    /** Called when the ML engine generates a fresh prediction. */
    public void recordPredictionGenerated(String commodity, String mandi) {
        Counter.builder("krishimitra.ml.predictions.generated")
                .tag("commodity", commodity)
                .tag("mandi",     mandi)
                .register(registry)
                .increment();
        lastPredictionGeneratedTs = System.currentTimeMillis();
    }

    /** Record profit simulation requests. */
    public void recordProfitSimulation(String commodity, int waitDays) {
        Counter.builder("krishimitra.profit.simulations")
                .tag("commodity", commodity)
                .tag("wait_days", String.valueOf(waitDays))
                .register(registry)
                .increment();
    }

    /** Record crop recommendation requests. */
    public void recordCropRecommendation(String season, String soilType) {
        Counter.builder("krishimitra.crop.recommendations")
                .tag("season",    season != null ? season : "unknown")
                .tag("soil_type", soilType != null ? soilType : "unknown")
                .register(registry)
                .increment();
    }
}
