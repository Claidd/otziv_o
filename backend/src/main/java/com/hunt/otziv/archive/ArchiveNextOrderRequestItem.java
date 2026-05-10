package com.hunt.otziv.archive;

import java.time.LocalDateTime;

public record ArchiveNextOrderRequestItem(
        Long id,
        Long companyId,
        Long filialId,
        Long sourceOrderId,
        Long createdOrderId,
        String status,
        int attempts,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
