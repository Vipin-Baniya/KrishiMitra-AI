package com.krishimitra.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateFarmerRequest(
        @Size(min = 2, max = 120) String name,
        @Email String email,
        String village,
        String district,
        String state,
        Double latitude,
        Double longitude,
        String preferredLang
) {}
