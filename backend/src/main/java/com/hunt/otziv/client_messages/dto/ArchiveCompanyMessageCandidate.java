package com.hunt.otziv.client_messages.dto;

import java.time.LocalDateTime;

public record ArchiveCompanyMessageCandidate(
        Long companyId,
        Long archiveOrderId,
        LocalDateTime statusChangedAt
) {
}
