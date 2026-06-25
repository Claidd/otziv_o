package com.hunt.otziv.specialist_transfer.dto;

import java.util.List;

public record SpecialistTransferRequest(
        Long fromWorkerId,
        Long toWorkerId,
        List<Long> companyIds,
        String comment,
        String confirmationText
) {
}
