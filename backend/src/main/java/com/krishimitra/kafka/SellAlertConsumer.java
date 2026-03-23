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
