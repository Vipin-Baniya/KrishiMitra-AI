package com.krishimitra.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CropRecommendationRequest(
        @NotBlank String location,
        String soilType,
        String season,
        String waterSource,
        @Positive double acres
) {}
