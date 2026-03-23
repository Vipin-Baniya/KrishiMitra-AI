package com.krishimitra.dto;

import java.util.UUID;

public record AlertResponse(
        UUID id,
        String type,
        String severity,
        String commodity,
        String mandiName,
        String title,
        String body,
        boolean isRead,
        String expiresAt,
        String createdAt
) {}
