package com.hunt.otziv.integration.outbox;

import java.time.LocalDateTime;

public record IntegrationOutboxStatusResponse(
        boolean relayEnabled,
        boolean diagnosticAvailable,
        String errorCode,
        int registeredHandlerCount,
        int batchSize,
        long leaseDurationMillis,
        CountSample due,
        CountSample processing,
        CountSample staleProcessing,
        CountSample dead,
        LocalDateTime databaseTime,
        LocalDateTime oldestDueAt,
        Long oldestDueAgeSeconds,
        long lastCycleCompletedEpochSeconds,
        long lastSuccessfulDeliveryEpochSeconds
) {
    public record CountSample(long value, boolean capped) {
    }
}
