package com.krishimitra.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record FarmerProfileResponse(
        UUID id,
        String name,
        String phone,
        String email,
        String village,
        String district,
        String state,
        String preferredLang,
        List<FarmerCropDto> crops,
        int unreadAlerts
) {
    public record FarmerCropDto(
            UUID id,
            String commodity,
            String variety,
            BigDecimal quantityQuintal,
            String expectedHarvest,
            boolean storageAvailable
    ) {}
}
