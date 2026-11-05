package com.hunt.otziv.worker_performance.dto;

/**
 * Result of an excuse or its undo. {@code changed=false} means the requested state already held
 * (repeated request); {@code laterDaysUpdated} counts later days whose streak was rewritten.
 */
public record WorkerDayExcuseResponse(
        boolean changed,
        String message,
        int laterDaysUpdated,
        WorkerDayResultResponse day
) {
}
