package com.krishimitra.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ProfitSimRequest(
        @NotBlank String commodity,
        @NotBlank String mandi,
        @Positive @Max(1000) double quantityQuintal,
        @Min(0) @Max(30) int waitDays
) {}
