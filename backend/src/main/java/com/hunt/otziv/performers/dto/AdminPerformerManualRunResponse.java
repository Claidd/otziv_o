package com.hunt.otziv.performers.dto;

public record AdminPerformerManualRunResponse(
        int createdAssignments,
        int expiredOffers,
        int offeredAssignments,
        int readyNotifications
) {
}
