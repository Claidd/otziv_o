package com.hunt.otziv.worker_performance.dto;

import java.time.LocalDate;
import java.util.List;

/** Final day results of the specialists visible to the caller; {@code editable} only for closed days. */
public record WorkerDayResultsResponse(
        LocalDate date,
        boolean editable,
        List<WorkerDayResultResponse> workers
) {
}
