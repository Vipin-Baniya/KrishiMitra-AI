package com.krishimitra.dto;

import java.util.List;

public record AlertPageResponse(
        List<AlertResponse> alerts,
        long totalUnread,
        int page,
        int totalPages
) {}
