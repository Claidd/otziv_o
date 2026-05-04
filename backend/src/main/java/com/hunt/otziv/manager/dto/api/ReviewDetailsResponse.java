package com.hunt.otziv.manager.dto.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ReviewDetailsResponse(
        Long id,
        Long companyId,
        UUID orderDetailsId,
        Long orderId,
        String text,
        String answer,
        String category,
        String subCategory,
        Long botId,
        String botFio,
        String botLogin,
        String botPassword,
        int botCounter,
        String companyTitle,
        String commentCompany,
        String orderComments,
        String filialCity,
        String filialTitle,
        String filialUrl,
        Long productId,
        String productTitle,
        boolean productPhoto,
        String workerFio,
        String created,
        String changed,
        String publishedDate,
        boolean publish,
        boolean vigul,
        String comment,
        BigDecimal price,
        String url,
        String urlPhoto
) {
}
