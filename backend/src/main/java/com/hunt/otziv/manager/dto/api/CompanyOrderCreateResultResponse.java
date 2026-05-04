package com.hunt.otziv.manager.dto.api;

public record CompanyOrderCreateResultResponse(
        Long companyId,
        String companyTitle,
        Long productId,
        String productTitle,
        Integer amount
) {
}
