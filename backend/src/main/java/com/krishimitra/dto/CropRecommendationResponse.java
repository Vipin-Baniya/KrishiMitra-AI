package com.krishimitra.dto;

import java.util.List;

public record CropRecommendationResponse(
        String location,
        String season,
        List<CropSuggestion> recommendations
) {
    public record CropSuggestion(
            String crop,
            String icon,
            int matchScore,
            String profitRange,
            String riskLevel,
            int growthDays,
            String bestMandi,
            String reason
    ) {}
}
