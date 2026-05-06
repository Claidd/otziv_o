package com.hunt.otziv.metric_snapshots.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MetricSnapshotSeenRequest(
        @NotBlank
        @Size(max = 40)
        String page,

        @NotBlank
        @Size(max = 80)
        String section,

        @Size(max = 120)
        String status,

        @Min(0)
        Integer value
) {
}
