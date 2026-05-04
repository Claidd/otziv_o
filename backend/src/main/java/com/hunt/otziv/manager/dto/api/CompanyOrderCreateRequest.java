package com.hunt.otziv.manager.dto.api;

public record CompanyOrderCreateRequest(
        Long productId,
        Integer amount,
        Long workerId,
        Long filialId
) {
}
