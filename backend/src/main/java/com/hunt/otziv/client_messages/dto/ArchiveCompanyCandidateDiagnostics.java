package com.hunt.otziv.client_messages.dto;

public record ArchiveCompanyCandidateDiagnostics(
        String status,
        long totalInStatus,
        long ready,
        long tooFresh,
        long withoutChat,
        long blockedByActiveOrder,
        long blockedByOpenRequest
) {
}
