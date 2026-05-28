package com.hunt.otziv.client_messages;

import java.time.LocalDateTime;
import java.util.List;

public record ClientMessageMonitorResponse(
        boolean enabled,
        boolean workerEnabled,
        boolean liveEnabled,
        boolean windowAllowed,
        String businessWindows,
        LocalDateTime nowIrkutsk,
        LocalDateTime updatedAt,
        LocalDateTime nextAttemptAt,
        LocalDateTime pausedUntil,
        String pauseReason,
        long activeCandidates,
        long dueNow,
        long sentToday,
        long failedToday,
        long skippedToday,
        long disabledStates,
        ArchiveDiagnostics archiveDiagnostics,
        List<ScenarioSummary> scenarios,
        List<QueueItem> queue,
        List<AttemptItem> attempts
) {
    public record ArchiveDiagnostics(
            String status,
            long totalInStatus,
            long ready,
            long tooFresh,
            long withoutChat,
            long blockedByActiveOrder,
            long blockedByOpenRequest
    ) {
    }

    public record ScenarioSummary(
            String scenario,
            String label,
            long activeCandidates,
            long dueNow,
            long sentToday,
            long sentSevenDays,
            long failedToday,
            long skippedToday,
            String lastError,
            LocalDateTime lastErrorAt
    ) {
    }

    public record QueueItem(
            Long id,
            String scenario,
            String scenarioLabel,
            String targetType,
            String targetKey,
            Long companyId,
            String companyTitle,
            Long orderId,
            String orderTitle,
            String statusTitle,
            LocalDateTime nextAttemptAt,
            LocalDateTime lastAttemptAt,
            LocalDateTime lastSuccessAt,
            String lastErrorCode,
            String lastErrorMessage,
            int sentCount,
            int consecutiveFailures,
            String expectedChannel,
            String channelDetails,
            String paymentInstructionSource,
            String messagePreview,
            String link
    ) {
    }

    public record AttemptItem(
            Long id,
            Long stateId,
            String scenario,
            String scenarioLabel,
            String targetType,
            String targetKey,
            Long companyId,
            String companyTitle,
            Long orderId,
            String orderTitle,
            String status,
            String statusLabel,
            String channel,
            String errorCode,
            String errorMessage,
            String messagePreview,
            Long durationMs,
            LocalDateTime attemptedAt,
            String link
    ) {
    }
}
