package com.krishimitra.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(min = 2, max = 120) String name,
        String email,
        String village,
        String district,
        String state,
        Double latitude,
        Double longitude,
        @Pattern(regexp = "^(en|hi|mr|gu|pa)$") String preferredLang
) {}
