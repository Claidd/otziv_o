package com.hunt.otziv.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ArchiveReviewRecoverySource(
        Long orderId,
        Long reviewId,
        Long companyId,
        UUID orderDetailsId,
        String orderStatus,
        String companyTitle,
        String companyNote,
        String companyChatUrl,
        String orderNote,
        Long managerId,
        Long workerId,
        Long botId,
        String botLogin,
        String botPassword,
        String botFio,
        Long filialCityId,
        String filialCity,
        String filialTitle,
        String filialUrl,
        String category,
        String subCategory,
        Long productId,
        String productTitle,
        String text,
        String answer,
        LocalDate created,
        LocalDate changed,
        LocalDate publishedDate,
        boolean publish,
        boolean vigul,
        BigDecimal price,
        String url
) {
}
