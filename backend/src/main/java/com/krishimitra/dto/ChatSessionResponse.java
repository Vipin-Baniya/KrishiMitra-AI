package com.krishimitra.dto;

import lombok.Builder;

@Builder
public record ChatSessionResponse(
        String id,
        String lastMessage,
        String createdAt
) {}
