package com.hunt.otziv.worker_performance.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/** Read-only, finalized prior-day goal results for assigned workers. */
public interface FinalWorkerDayResults {
    Map<Long, WorkerDayResult> workerDayResults(Collection<Long> workerIds, LocalDate date);

    record WorkerDayResult(Long workerId, long eligibleCount, long completedCount, boolean reached100) {
    }
}
