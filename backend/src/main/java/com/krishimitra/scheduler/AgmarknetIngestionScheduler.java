package com.krishimitra.scheduler;

import com.krishimitra.client.AgmarknetClient;
import com.krishimitra.kafka.KafkaProducer;
import com.krishimitra.model.entity.Mandi;
import com.krishimitra.model.entity.MandiPrice;
import com.krishimitra.repository.MandiPriceRepository;
import com.krishimitra.repository.MandiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgmarknetIngestionScheduler {

    private final AgmarknetClient agmarknetClient;
    private final MandiPriceRepository priceRepo;
    private final MandiRepository mandiRepo;
    private final KafkaProducer kafkaProducer;

    private static final DateTimeFormatter AGMARKNET_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<String> COMMODITIES = List.of(
            "Wheat", "Soybean", "Onion", "Tomato", "Potato",
            "Cotton", "Maize", "Gram", "Paddy(Dhan)(Common)"
    );

    private static final List<String> STATES = List.of(
            "Madhya Pradesh", "Maharashtra", "Rajasthan",
            "Punjab", "Haryana", "Uttar Pradesh", "Gujarat"
    );

    /**
     * Runs daily at 5:30 AM IST — after mandi data is published on Agmarknet.
     * Fetches yesterday's prices for all commodity × state combinations.
     */
    @Scheduled(cron = "${krishimitra.agmarknet.ingest-schedule}",
               zone  = "Asia/Kolkata")
    public void ingestDailyPrices() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String dateStr = yesterday.format(AGMARKNET_DATE);

        log.info("Starting Agmarknet ingestion for date: {}", dateStr);
        int totalInserted = 0;

        for (String commodity : COMMODITIES) {
            for (String state : STATES) {
                try {
                    int n = ingestForCommodityState(commodity, state, dateStr, dateStr);
                    totalInserted += n;
                    if (n > 0) {
                        kafkaProducer.publishPriceIngested(commodity, state, n);
                    }
                    Thread.sleep(500);  // be polite to the API
                } catch (Exception e) {
                    log.error("Ingestion failed for {}@{}: {}", commodity, state, e.getMessage());
                }
            }
        }

        log.info("Agmarknet ingestion complete: {} records inserted for {}", totalInserted, dateStr);
    }

    @Transactional
    public int ingestForCommodityState(
            String commodity, String state, String fromDate, String toDate) {

        List<AgmarknetClient.AgmarknetRecord> records =
                agmarknetClient.fetchPrices(commodity, state, fromDate, toDate);

        List<MandiPrice> toSave = new ArrayList<>();

        for (AgmarknetClient.AgmarknetRecord r : records) {
            // Find or create the mandi
            String mandiName = r.market() != null ? r.market().trim() : "Unknown";
            if (mandiName.isBlank()) continue;

            Mandi mandi = mandiRepo
                    .findByNameIgnoreCaseAndStateIgnoreCase(mandiName, state)
                    .orElseGet(() -> {
                        Mandi m = Mandi.builder()
                                .name(mandiName)
                                .district(r.district() != null ? r.district() : state)
                                .state(state)
                                .isActive(true)
                                .build();
                        return mandiRepo.save(m);
                    });

            // Parse date
            LocalDate priceDate;
            try {
                priceDate = LocalDate.parse(r.arrival_date(), AGMARKNET_DATE);
            } catch (Exception e) {
                continue;
            }

            // Check for existing record (avoid duplicate)
            if (priceRepo.findIdForUpsert(mandi.getId(), commodity, priceDate).isPresent()) {
                continue;
            }

            // Parse prices (Agmarknet returns strings)
            try {
                BigDecimal minP  = new BigDecimal(r.min_price().trim());
                BigDecimal maxP  = new BigDecimal(r.max_price().trim());
                BigDecimal modal = new BigDecimal(r.modal_price().trim());
                if (modal.compareTo(BigDecimal.ZERO) <= 0) continue;

                MandiPrice mp = MandiPrice.builder()
                        .mandi(mandi)
                        .commodity(commodity)
                        .variety(r.variety() != null ? r.variety() : "Common")
                        .priceDate(priceDate)
                        .minPrice(minP)
                        .maxPrice(maxP)
                        .modalPrice(modal)
                        .arrivalsQtl(r.arrivals_in_qtl() != null && !r.arrivals_in_qtl().isBlank()
                                ? new BigDecimal(r.arrivals_in_qtl().trim()) : null)
                        .source("agmarknet")
                        .build();
                toSave.add(mp);

                // Publish price update to Kafka for alert engine
                kafkaProducer.publishPriceUpdate(commodity, mandiName, state, modal, null, fromDate);

            } catch (NumberFormatException e) {
                log.debug("Skipping invalid price record: {}", r);
            }
        }

        if (!toSave.isEmpty()) {
            priceRepo.saveAll(toSave);
            log.debug("Saved {} price records for {}@{}", toSave.size(), commodity, state);
        }

        return toSave.size();
    }

    /**
     * Nightly cleanup: remove expired predictions and alerts.
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Kolkata")
    public void cleanup() {
        log.info("Running nightly cleanup");
        // These are called via their respective repos in the service layer
        // (omitted to keep this file focused on scheduling)
    }
}
