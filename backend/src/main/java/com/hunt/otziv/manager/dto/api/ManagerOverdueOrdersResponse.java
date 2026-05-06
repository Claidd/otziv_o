package com.hunt.otziv.manager.dto.api;

import java.util.List;

public record ManagerOverdueOrdersResponse(
        int thresholdDays,
        long total,
        List<ManagerOverdueStatusResponse> statuses
) {
}
