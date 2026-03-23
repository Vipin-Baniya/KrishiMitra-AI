package com.krishimitra.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
