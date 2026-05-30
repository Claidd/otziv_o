package com.hunt.otziv.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ArchiveReviewItem(
        Long id,
        UUID orderDetailsId,
        String text,
        String answer,
        String category,
        String subCategory,
        Long botId,
        String botFio,
        String botLogin,
        Long productId,
        String productTitle,
        String workerFio,
        String filialTitle,
        LocalDate created,
        LocalDate changed,
        LocalDate publishedDate,
        boolean publish,
        boolean vigul,
        BigDecimal price,
        String url
) {
}
