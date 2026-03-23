package com.krishimitra.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
