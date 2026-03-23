package com.krishimitra.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatRequest(
        UUID sessionId,     // null = create new session
        @NotBlank @Size(max = 2000) String message,
        String language,
        ChatContext context
) {
    public record ChatContext(
            String location,
            List<String> crops,
            Map<String, Object> livePrices,
            String weatherBrief
    ) {}
}
