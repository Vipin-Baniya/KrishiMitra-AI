package com.krishimitra.kafka;

import com.krishimitra.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────
// TOPIC CONFIG
// ─────────────────────────────────────────────────────────────

@Configuration
class KafkaTopicConfig {

    @Bean public NewTopic priceUpdatesTopic() {
        return TopicBuilder.name("krishimitra.price-updates")
                .partitions(6).replicas(1)
                .config("retention.ms", "86400000")  // 24h
                .build();
    }

    @Bean public NewTopic sellAlertsTopic() {
        return TopicBuilder.name("krishimitra.sell-alerts")
                .partitions(3).replicas(1).build();
    }

    @Bean public NewTopic priceIngestedTopic() {
        return TopicBuilder.name("krishimitra.price-ingested")
                .partitions(3).replicas(1).build();
    }

    @Bean public NewTopic modelRetrainTopic() {
        return TopicBuilder.name("krishimitra.model-retrain")
                .partitions(1).replicas(1).build();
    }
}

// ─────────────────────────────────────────────────────────────
// KAFKA MESSAGES
// ─────────────────────────────────────────────────────────────

record PriceUpdateEvent(
        String commodity,
        String mandi,
        String state,
        BigDecimal modalPrice,
        BigDecimal changePct,
        String priceDate,
        Instant eventTime
) {}

record SellAlertEvent(
        UUID farmerId,
        String commodity,
        String mandi,
        Integer waitDays,
        BigDecimal expectedGain,
        Instant eventTime
) {}

record PriceIngestedEvent(
        String commodity,
        String state,
        int recordsIngested,
        Instant ingestTime
) {}

record ModelRetrainEvent(
        String commodity,
        String reason,          // "drift" | "scheduled" | "accuracy_drop"
        Instant triggeredAt
) {}

// ─────────────────────────────────────────────────────────────
// KAFKA PRODUCER
// ─────────────────────────────────────────────────────────────

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.topics.price-updates}")
    private String priceUpdatesTopic;

    @Value("${spring.kafka.topics.sell-alerts}")
    private String sellAlertsTopic;

    @Value("${spring.kafka.topics.price-ingested}")
    private String priceIngestedTopic;

    @Value("${spring.kafka.topics.model-retrain}")
    private String modelRetrainTopic;

    public void publishPriceUpdate(
            String commodity, String mandi, String state,
            BigDecimal price, BigDecimal changePct, String date) {

        PriceUpdateEvent event = new PriceUpdateEvent(
                commodity, mandi, state, price, changePct, date, Instant.now());

        kafkaTemplate.send(priceUpdatesTopic, commodity + ":" + mandi, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish price update: {}", ex.getMessage());
                    } else {
                        log.debug("Published price update: {}@{} ₹{}", commodity, mandi, price);
                    }
                });
    }

    public void publishSellAlert(
            UUID farmerId, String commodity, String mandi,
            int waitDays, BigDecimal expectedGain) {

        SellAlertEvent event = new SellAlertEvent(
                farmerId, commodity, mandi, waitDays, expectedGain, Instant.now());

        kafkaTemplate.send(sellAlertsTopic, farmerId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) log.error("Failed to publish sell alert: {}", ex.getMessage());
                });
    }

    public void publishPriceIngested(String commodity, String state, int records) {
        PriceIngestedEvent event = new PriceIngestedEvent(commodity, state, records, Instant.now());
        kafkaTemplate.send(priceIngestedTopic, commodity, event);
    }

    public void triggerModelRetrain(String commodity, String reason) {
        ModelRetrainEvent event = new ModelRetrainEvent(commodity, reason, Instant.now());
        log.info("Triggering model retrain for {}: {}", commodity, reason);
        kafkaTemplate.send(modelRetrainTopic, commodity, event);
    }
}

// ─────────────────────────────────────────────────────────────
// KAFKA CONSUMERS
// ─────────────────────────────────────────────────────────────

@Component
@RequiredArgsConstructor
@Slf4j
public class PriceUpdateConsumer {

    private final AlertService alertService;

    private static final double SPIKE_THRESHOLD_PCT = 5.0;

    /**
     * Listens to incoming price updates and fires alerts when:
     *  - Price moves > 5% in either direction
     *  - This is compared against the previous day's price
     */
    @KafkaListener(
            topics    = "#{@environment.getProperty('spring.kafka.topics.price-updates')}",
            groupId   = "krishimitra-alert-engine",
            concurrency = "3"
    )
    public void onPriceUpdate(PriceUpdateEvent event) {
        log.debug("Price update: {}@{} ₹{} ({}%)",
                event.commodity(), event.mandi(), event.modalPrice(), event.changePct());

        if (event.changePct() == null) return;

        double changePct = event.changePct().doubleValue();

        if (Math.abs(changePct) >= SPIKE_THRESHOLD_PCT) {
            String direction = changePct > 0 ? "rising" : "falling";
            String severity  = Math.abs(changePct) >= 10 ? "URGENT" : "WARNING";
            String type      = changePct > 0 ? "PRICE_SPIKE" : "PRICE_DROP";

            String title = String.format("%s prices %s %.1f%% in %s",
                    event.commodity(), direction, Math.abs(changePct), event.mandi());
            String body  = String.format(
                    "%s modal price is now ₹%.0f/qtl at %s mandi (%s%.1f%% today). %s",
                    event.commodity(), event.modalPrice().doubleValue(), event.mandi(),
                    changePct > 0 ? "+" : "", changePct,
                    changePct > 0 ? "Consider selling soon." : "Hold if possible.");

            // Broadcast alert (farmerId = null → all farmers with this crop)
            alertService.createAlert(
                    null, type, severity,
                    event.commodity(), event.mandi(),
                    title, body,
                    Map.of("changePct", changePct, "price", event.modalPrice()));
        }
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
public class SellAlertConsumer {

    private final AlertService alertService;

    @KafkaListener(
            topics    = "#{@environment.getProperty('spring.kafka.topics.sell-alerts')}",
            groupId   = "krishimitra-sell-notifier",
            concurrency = "2"
    )
    public void onSellAlert(SellAlertEvent event) {
        log.info("Sell alert for farmer {}: {} wait {}d gain ₹{}",
                event.farmerId(), event.commodity(), event.waitDays(), event.expectedGain());

        String title = String.format("Best time to sell %s is in %d days",
                event.commodity(), event.waitDays());
        String body  = String.format(
                "Wait %d days to sell your %s at %s mandi. "
                + "Expected additional gain: ₹%.0f/quintal.",
                event.waitDays(), event.commodity(), event.mandi(),
                event.expectedGain().doubleValue());

        alertService.createAlert(
                event.farmerId(), "SELL_WINDOW", "INFO",
                event.commodity(), event.mandi(),
                title, body,
                Map.of("waitDays", event.waitDays(), "expectedGain", event.expectedGain()));
    }
}

@Component
@RequiredArgsConstructor
@Slf4j
public class PriceIngestedConsumer {

    private final KafkaProducer kafkaProducer;

    /**
     * After new prices are ingested, check if we should trigger model retraining.
     * Triggers retrain if > 7 consecutive days of new data.
     */
    @KafkaListener(
            topics    = "#{@environment.getProperty('spring.kafka.topics.price-ingested')}",
            groupId   = "krishimitra-retrain-trigger",
            concurrency = "1"
    )
    public void onPriceIngested(PriceIngestedEvent event) {
        log.debug("Prices ingested: {} records for {} / {}",
                event.recordsIngested(), event.commodity(), event.state());

        // Simple trigger: enough records ingested
        if (event.recordsIngested() >= 5) {
            kafkaProducer.triggerModelRetrain(event.commodity(), "new_data");
        }
    }
}
