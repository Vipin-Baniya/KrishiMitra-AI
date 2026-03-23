package com.krishimitra.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record SellAdviceRequest(
        @NotBlank String commodity,
        @NotBlank String mandi,
        @Positive double quantityQuintal,
        boolean storageAvailable
) {}
