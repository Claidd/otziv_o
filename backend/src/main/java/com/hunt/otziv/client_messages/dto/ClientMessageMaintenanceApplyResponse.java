package com.hunt.otziv.client_messages.dto;

import java.time.LocalDateTime;

public record ClientMessageMaintenanceApplyResponse(
        String action,
        long changed,
        String message,
        LocalDateTime appliedAt,
        ClientMessageMaintenancePreviewResponse preview
) {
}
