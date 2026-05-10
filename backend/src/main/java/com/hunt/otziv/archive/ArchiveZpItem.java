package com.hunt.otziv.archive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ArchiveZpItem(
        Long id,
        String fio,
        BigDecimal sum,
        Long userId,
        Long professionId,
        Long orderId,
        int amount,
        LocalDate created,
        boolean active
) {
}
