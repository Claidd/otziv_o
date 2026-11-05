package com.hunt.otziv.worker_performance.dto;

/** Owner decision to count (or stop counting) a specialist's day; the reason is mandatory. */
public record WorkerDayExcuseRequest(String reason) {
}
