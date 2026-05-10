package com.hunt.otziv.archive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ArchivePaymentCheckItem(
        Long id,
        String title,
        BigDecimal sum,
        Long companyId,
        Long orderId,
        Long managerId,
        Long workerId,
        LocalDate created,
        boolean active
) {
}
