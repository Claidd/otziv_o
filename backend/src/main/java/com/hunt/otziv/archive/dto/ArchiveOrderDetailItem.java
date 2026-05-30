package com.hunt.otziv.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ArchiveOrderDetailItem(
        UUID id,
        Long productId,
        String productTitle,
        Integer amount,
        BigDecimal price,
        String comment,
        LocalDate publishedDate
) {
}
