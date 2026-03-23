package com.krishimitra.kafka;

import com.krishimitra.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

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
