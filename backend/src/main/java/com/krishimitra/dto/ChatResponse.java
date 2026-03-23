package com.krishimitra.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatResponse(
        UUID sessionId,
        String content,
        String modelUsed,
        Double confidence,
        Long latencyMs,
        Instant createdAt
) {}
