package com.hunt.otziv.manager.dto.api;

import java.util.List;

public record CompanyOrderCreateResponse(
        Long companyId,
        String companyTitle,
        List<OrderProductResponse> products,
        List<Integer> amounts,
        List<OptionResponse> workers,
        List<FilialResponse> filials,
        Long defaultProductId,
        Integer defaultAmount,
        Long defaultWorkerId,
        Long defaultFilialId
) {
}
