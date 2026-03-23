package com.krishimitra.dto;

import java.math.BigDecimal;

public record LivePriceResponse(
        String commodity,
        String mandi,
        String district,
        String state,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal modalPrice,
        BigDecimal arrivalsQtl,
        String priceDate,
        String trendDirection,  // UP | DOWN | FLAT
        BigDecimal changePct
) {}
