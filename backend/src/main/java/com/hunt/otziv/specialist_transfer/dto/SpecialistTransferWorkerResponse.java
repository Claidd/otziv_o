package com.hunt.otziv.specialist_transfer.dto;

public record SpecialistTransferWorkerResponse(
        Long id,
        Long userId,
        String username,
        String fio,
        String label,
        boolean active
) {
}
