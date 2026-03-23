package com.krishimitra.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
